"""Module for evaluating policies."""
from typing import Dict, List, Optional, Callable

from pydantic import AnyHttpUrl

from fidesctl.cli.utils import handle_cli_response, pretty_echo
from fidesctl.core import api
from fidesctl.core.api_helpers import get_server_resources, get_server_resource
from fidesctl.core.parse import parse
from fidesctl.core.utils import echo_green, echo_red
from fideslang.models import (
    Evaluation,
    EvaluationError,
    StatusEnum,
    InclusionEnum,
    Policy,
    Taxonomy,
)
from fideslang.validation import FidesKey
from fideslang.relationships import (
    get_referenced_missing_keys,
    hydrate_missing_resources,
)


def get_evaluation_policies(
    local_policies: List[Policy],
    evaluate_fides_key: str,
    url: AnyHttpUrl,
    headers: Dict[str, str],
) -> List[Policy]:
    """
    Returns policies to be evaluated. If 'evaluate_fides_key' is
    passed then only that policy will be returned. Otherwise, returns
    all policies.
    """
    if evaluate_fides_key:
        local_policy_found = next(
            filter(
                lambda policy: policy.fides_key == evaluate_fides_key, local_policies
            ),
            None,
        )
        if local_policy_found:
            return [local_policy_found]

        server_policy_found = get_server_resource(
            url=url,
            resource_type="policy",
            resource_key=evaluate_fides_key,
            headers=headers,
        )
        return [server_policy_found] if server_policy_found else []

    local_policy_keys = (
        [policy.fides_key for policy in local_policies] if local_policies else None
    )
    all_policies = local_policies + get_all_server_policies(
        url=url, headers=headers, exclude=local_policy_keys
    )
    return all_policies


def get_all_server_policies(
    url: AnyHttpUrl, headers: Dict[str, str], exclude: Optional[List[FidesKey]] = None
) -> List[Policy]:
    """
    Get a list of all of the Policies that exist on the server.

    If 'exclude' is passed those specific Policies won't be pulled from the server.
    """

    exclude = exclude if exclude else []
    ls_response = handle_cli_response(
        api.ls(url=url, resource_type="policy", headers=headers), verbose=False
    )
    policy_keys = [
        resource["fides_key"]
        for resource in ls_response.json()
        if resource["fides_key"] not in exclude
    ]
    policy_list = get_server_resources(
        url=url, resource_type="policy", headers=headers, existing_keys=policy_keys
    )
    return policy_list


def validate_policies_exist(policies: List[Policy], evaluate_fides_key: str) -> None:
    """
    Validates that policies to be evaluated exist. If no policies were found
    raises an error and logs an error.
    """
    if not policies:
        echo_red(
            "Policy {} could not be found".format(evaluate_fides_key)
            if evaluate_fides_key
            else "No Policies found to evaluate"
        )
        raise EvaluationError


def compare_rule_to_declaration(
    rule_types: List[FidesKey],
    declaration_types: List[FidesKey],
    rule_inclusion: InclusionEnum,
) -> bool:
    """
    Compare the list of fides_keys within the rule against the list
    of fides_keys from the declaration and use the rule's inclusion
    field to determine whether the rule is triggered or not.
    """
    inclusion_map: Dict[InclusionEnum, Callable] = {
        InclusionEnum.ANY: any,
        InclusionEnum.ALL: all,
        InclusionEnum.NONE: lambda x: not any(x),
    }

    matching_data_categories = [
        bool(data_category in rule_types) for data_category in declaration_types
    ]
    result = inclusion_map[rule_inclusion](matching_data_categories)
    return result


def execute_evaluation(taxonomy: Taxonomy) -> Evaluation:
    """
    Check the stated constraints of each Privacy Policy's rules against
    what is declared each system's privacy declarations.
    """

    evaluation_detail_list = []
    for policy in taxonomy.policy:
        for rule in policy.rules:
            for system in taxonomy.system:
                for declaration in system.privacy_declarations:

                    data_category_result = compare_rule_to_declaration(
                        rule_types=rule.data_categories.values,
                        declaration_types=declaration.data_categories,
                        rule_inclusion=rule.data_categories.inclusion,
                    )

                    # A declaration only has one data use, so it gets put in a list
                    data_use_result = compare_rule_to_declaration(
                        rule_types=rule.data_uses.values,
                        declaration_types=[declaration.data_use],
                        rule_inclusion=rule.data_uses.inclusion,
                    )

                    data_subject_result = compare_rule_to_declaration(
                        rule_types=rule.data_subjects.values,
                        declaration_types=declaration.data_subjects,
                        rule_inclusion=rule.data_subjects.inclusion,
                    )

                    data_qualifier_result = (
                        declaration.data_qualifier == rule.data_qualifier
                    )

                    if all(
                        [
                            data_category_result,
                            data_subject_result,
                            data_use_result,
                            data_qualifier_result,
                        ]
                    ):
                        evaluation_detail_list += [
                            "Declaration ({}) of System ({}) failed Rule ({}) from Policy ({})".format(
                                declaration.name,
                                system.fides_key,
                                rule.name,
                                policy.fides_key,
                            )
                        ]

    status_enum = (
        StatusEnum.FAIL if len(evaluation_detail_list) > 0 else StatusEnum.PASS
    )
    evaluation = Evaluation(status=status_enum, details=evaluation_detail_list)
    return evaluation


def evaluate(
    url: AnyHttpUrl,
    manifests_dir: str,
    fides_key: str,
    headers: Dict[str, str],
    message: str,
    dry: bool,
) -> Evaluation:
    """
    Perform evaluation for a given Policy. If a policy key is not
    provided, perform an evaluation for all of the Policies in an organzation

    Local Policy definition files will be used as opposed to their
    server-definitions if available.
    """
    taxonomy = parse(manifests_dir)

    # Populate all of the policies to evaluate
    taxonomy.policy = get_evaluation_policies(
        local_policies=taxonomy.policy,
        evaluate_fides_key=fides_key,
        url=url,
        headers=headers,
    )
    validate_policies_exist(policies=taxonomy.policy, evaluate_fides_key=fides_key)

    echo_green(
        "Evaluating the following policies:\n{}".format(
            "\n".join([key.fides_key for key in taxonomy.policy])
        )
    )
    print("-" * 10)

    echo_green("Checking for missing resources...")
    missing_resource_keys = get_referenced_missing_keys(taxonomy)
    if missing_resource_keys:
        echo_green(
            "Fetching the following missing resources from the server:\n{}".format(
                "\n".join(missing_resource_keys)
            )
        )
        echo_green("Hydrating the taxonomy...")
        taxonomy = hydrate_missing_resources(
            url=url,
            headers=headers,
            missing_resource_keys=missing_resource_keys,
            dehydrated_taxonomy=taxonomy,
        )

    echo_green("Executing evaluations...")
    evaluation = execute_evaluation(taxonomy)
    evaluation.message = message
    if evaluation.status == "FAIL":
        pretty_echo(evaluation.dict(), color="red")
        raise EvaluationError

    ## This is waiting for the server to have an /evaluations endpoint
    if not dry:
        echo_green("Sending the evaluation results to the server...")
    echo_green("Evaluation passed!")

    return evaluation
