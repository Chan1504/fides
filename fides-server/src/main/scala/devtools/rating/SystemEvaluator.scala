package devtools.rating

import devtools.domain._
import devtools.domain.enums._
import devtools.domain.policy.{Declaration, Policy, PolicyRule}
import devtools.persist.dao.DAOs
import devtools.util.mapGrouping

import scala.concurrent.ExecutionContext

final case class SystemEvaluation(
  /** map of status to {policy.rule -> [matching declaration names]} */
  statusMap: Map[ApprovalStatus, Map[String, Seq[String]]],
  /** A list of warning strings collected as we cycle through evaluation rules */
  warnings: Seq[String],
  /** A list of error strings collected as we cycle through evaluation rules */
  errors: Seq[String],
  overallApproval: ApprovalStatus
) {

  def toMap: Map[String, Any] =
    statusMap.map(t => t._1.toString -> t._2) ++ Seq("warnings" -> warnings, "errors" -> errors)
}

/** Updated version of ratings */
class SystemEvaluator(val daos: DAOs)(implicit val executionContext: ExecutionContext) {

  private val policyRuleEvaluator = new PolicyRuleEvaluator(daos)
  /** Check system declarations and warn if there are declared types in the dataset that have not been declared
    * in the system
    */
  private def checkDependentDatasetPrivacyDeclaration(
    systemObject: SystemObject,
    datasets: Iterable[Dataset]
  ): Seq[String] = {
    // collect all of the data categories matching the given data qualifier, including children.
    def categoriesForQualifier(dataQualifier: DataQualifierName, dataset: Dataset): Set[DataCategoryName] = {
      val categories: Set[DataCategoryName] = dataset.categoriesForQualifiers(
        daos.dataQualifierDAO.childrenOfInclusive(systemObject.organizationId, dataQualifier)
      )
      daos.dataCategoryDAO.mergeAndReduce(systemObject.organizationId, categories)
    }

    val categoriesByQualifierInSystem: Map[DataQualifierName, Set[DataCategoryName]] = systemObject.declarations
      .map(d => (d.dataQualifier, d.dataCategories))
      .groupBy(_._1)
      .map((t: (DataQualifierName, Seq[(DataQualifierName, Set[DataCategoryName])])) => t._1 -> t._2.map(_._2))
      .map(t => t._1 -> daos.dataCategoryDAO.mergeAndReduce(systemObject.organizationId, t._2.flatten.toSet))

    var warnings = Seq[String]()

    datasets.foreach(ds =>
      categoriesByQualifierInSystem.foreach {
        case (qualifier, categories) =>
          val datasetCategories = categoriesForQualifier(qualifier, ds)
          val diff              = datasetCategories.diff(categories)
          if (diff.nonEmpty) {
            val s =
              s"Dataset:${ds.fidesKey}: These categories exist for qualifer $qualifier in this dataset but do not appear with that qualifier in the dependant system ${systemObject.fidesKey}:[${diff
                .mkString(",")}]"
            warnings = warnings :+ s
          }
      }
    )

    warnings
  }

  /** for all dependent systems, check if privacy declarations includes things that are not in the privacy set of this system.
    * Assumes that all dependent systems are populated in the dependentSystems variable
    */
  def checkDeclarationsOfDependentSystems(
    systemObject: SystemObject,
    dependentSystems: Iterable[SystemObject]
  ): Seq[String] = {
    val mergedDependencies = mergeDeclarations(systemObject.organizationId, systemObject.declarations)
    var warnings           = Seq[String]()

    dependentSystems.foreach(ds => {
      val mergedDependenciesForDs = mergeDeclarations(systemObject.organizationId, ds.declarations)
      val diff                    = diffDeclarations(systemObject.organizationId, mergedDependenciesForDs, mergedDependencies)
      if (diff.nonEmpty) {
        warnings =
          warnings :+ s"The system ${ds.fidesKey} includes privacy declarations that do not exist in ${systemObject.fidesKey} : ${diff.map(_.name).mkString(",")}"
      }
    })

    warnings
  }

  def checkDependentDatasetsExist(system: SystemObject, dependentDatasets: Seq[Dataset]): Seq[String] =
    system.datasets.diff(dependentDatasets.map(_.fidesKey).toSet) match {
      case missing if missing.nonEmpty => Seq(s"The referenced datasets ${missing.mkString(",")} were not found.")
      case _                           => Seq()
    }
  def checkDependentSystemsExist(system: SystemObject, dependentSystems: Seq[SystemObject]): Seq[String] =
    system.systemDependencies.diff(dependentSystems.map(_.fidesKey).toSet) match {
      case missing if missing.nonEmpty => Seq(s"The referenced datasets ${missing.mkString(",")} were not found.")
      case _                           => Seq()
    }

  /** return map of approval status -> [rules that returned that rating]
    *
    * e.g (FAIL -> (policy1.rule1 -> [declarationName1, declarationName2], policy2.rule2 -> [declarationName2])
    */
  def evaluatePolicyRules(
    policies: Seq[Policy],
    system: SystemObject
  ): Map[ApprovalStatus, Map[String, Seq[String]]] = {

    val v: Seq[(Option[PolicyAction], String, String)] = for {
      d                <- system.declarations
      policy           <- policies
      rule: PolicyRule <- policy.rules.getOrElse(Set())
      action: Option[PolicyAction] = policyRuleEvaluator.matches(rule, d)
    } yield (action, policy.fidesKey + "." + rule.fidesKey, d.name)

    val collectByAction = v.collect {
      case (Some(rating), ruleName, declarationName) => (toApprovalStatus(rating), ruleName, declarationName)
    }

    val groupByStatus = mapGrouping(collectByAction, t => t.productElement(2), Seq(0, 1))
      .asInstanceOf[Map[ApprovalStatus, Map[String, Seq[String]]]]

    //if there are any non-pass statuses, filter out the "pass" values
    if (groupByStatus.exists(_._1 != ApprovalStatus.PASS)) {
      groupByStatus.filter(_._1 != ApprovalStatus.PASS)
    } else {
      groupByStatus
    }
  }

  def evaluateSystem(
    system: SystemObject,
    dependentSystems: Seq[SystemObject],
    dependentDatasets: Seq[Dataset],
    policies: Seq[Policy]
  ): SystemEvaluation = {
    val m: Map[ApprovalStatus, Map[String, Seq[String]]] = evaluatePolicyRules(policies, system)

    val warnings = checkDependentDatasetPrivacyDeclaration(
      system,
      dependentDatasets
    ) ++ checkDeclarationsOfDependentSystems(system, dependentSystems)
    val errors =
      checkDependentDatasetsExist(system, dependentDatasets) ++ checkDependentSystemsExist(system, dependentSystems)

    val overallApproval = {
      if (errors.nonEmpty) {
        ApprovalStatus.ERROR
      } else if (m.contains(ApprovalStatus.FAIL)) {
        ApprovalStatus.FAIL
      } else if (m.contains(ApprovalStatus.MANUAL)) {
        ApprovalStatus.MANUAL
      } else {
        ApprovalStatus.PASS
      }
    }

    SystemEvaluation(m, warnings, errors, overallApproval)
  }

  // ------------------  support functions ------------------

  private def toApprovalStatus(action: PolicyAction): ApprovalStatus =
    action match {
      case PolicyAction.ACCEPT  => ApprovalStatus.PASS
      case PolicyAction.REJECT  => ApprovalStatus.FAIL
      case PolicyAction.REQUIRE => ApprovalStatus.MANUAL
    }
  /**
    * For all declarations
    *  - match:
    *    - they have the same data use
    *    - they have the same data qualifier
    *
    * data subject categories are the mergeDeclarations of both data subject categories
    * data categories are the mergeDeclarations of both data categories
    *
    * where "mergeDeclarations" means:
    *      - mergeAndReduce both sets of values
    *      - reduce both sets of values using TreeCache mergeAndReduce
    *
    * Return a sequence of [{set of declaration names that were used to generate this merge}, merged declaration}]
    */

  def mergeDeclarations(organizationId: Long, declarations: Iterable[Declaration]): Seq[Declaration] = {
    declarations
      .groupBy(d => (d.dataQualifier, d.dataUse))
      .map { t: ((DataQualifierName, DataUseName), Iterable[Declaration]) =>
        val categories = daos.dataCategoryDAO.mergeAndReduce(organizationId, t._2.flatMap(_.dataCategories).toSet)
        val subjectCategories =
          daos.dataSubjectCategoryDAO.mergeAndReduce(organizationId, t._2.flatMap(_.dataSubjectCategories).toSet)
        Declaration(t._2.map(_.name).toSet.mkString(","), categories, t._1._2, t._1._1, subjectCategories)
      }
      .toSeq
  }

  def diffDeclarations(organizationId: Long, a: Iterable[Declaration], b: Iterable[Declaration]): Set[Declaration] = {

    val l = mergeDeclarations(organizationId, a)
    val r = mergeDeclarations(organizationId, b)

    val rGrouped: Map[(DataQualifierName, DataUseName), Seq[Declaration]] = r.groupBy(d => (d.dataQualifier, d.dataUse))
    val lGrouped: Map[(DataQualifierName, DataUseName), Seq[Declaration]] = l.groupBy(d => (d.dataQualifier, d.dataUse))

    val grouped: Seq[Declaration] = lGrouped.map { t: ((DataQualifierName, DataUseName), Seq[Declaration]) =>
      val rVals: Seq[Declaration] = rGrouped.getOrElse(t._1, Seq())
      val rCategories             = rVals.flatMap(_.dataCategories).toSet
      val rSubjectCategories      = rVals.flatMap(_.dataSubjectCategories).toSet

      val lCategories        = t._2.flatMap(_.dataCategories).toSet
      val lSubjectCategories = t._2.flatMap(_.dataSubjectCategories).toSet

      val categoryDiff = daos.dataCategoryDAO.diff(organizationId, rCategories, lCategories)
      val subjectCategoryDiff =
        daos.dataSubjectCategoryDAO.diff(organizationId, rSubjectCategories, lSubjectCategories)

      Declaration(t._2.map(_.name).mkString(","), categoryDiff, t._1._2, t._1._1, subjectCategoryDiff)
    }.toSeq

    grouped.filter(d => d.dataCategories.nonEmpty || d.dataSubjectCategories.nonEmpty).toSet
  }
}