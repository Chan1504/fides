"""Add legal_basis, purpose_of_processing, and recipient to data_use model

Revision ID: d5807bacca0a
Revises: 7c851d8a102a
Create Date: 2022-01-25 03:47:42.451775

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision = 'd5807bacca0a'
down_revision = '7c851d8a102a'
branch_labels = None
depends_on = None


def upgrade():
    # ### commands auto generated by Alembic - please adjust! ###
    op.add_column('data_uses', sa.Column('legal_basis', sa.Text(), nullable=True))
    op.add_column('data_uses', sa.Column('purpose_of_processing', sa.Text(), nullable=True))
    op.add_column('data_uses', sa.Column('recipient', sa.Text(), nullable=True))
    op.drop_column('evaluations', 'updated_at')
    op.drop_column('evaluations', 'created_at')
    # ### end Alembic commands ###


def downgrade():
    # ### commands auto generated by Alembic - please adjust! ###
    op.add_column('evaluations', sa.Column('created_at', postgresql.TIMESTAMP(timezone=True), server_default=sa.text('now()'), autoincrement=False, nullable=True))
    op.add_column('evaluations', sa.Column('updated_at', postgresql.TIMESTAMP(timezone=True), server_default=sa.text('now()'), autoincrement=False, nullable=True))
    op.drop_column('data_uses', 'recipient')
    op.drop_column('data_uses', 'purpose_of_processing')
    op.drop_column('data_uses', 'legal_basis')
    # ### end Alembic commands ###
