package cosmos.util.sql;

import java.util.List;

import org.eigenbase.rel.RelNode;
import org.eigenbase.rel.TableAccessRelBase;
import org.eigenbase.relopt.RelOptCluster;
import org.eigenbase.relopt.RelOptCost;
import org.eigenbase.relopt.RelOptPlanWriter;
import org.eigenbase.relopt.RelOptPlanner;
import org.eigenbase.relopt.RelOptTable;
import org.eigenbase.relopt.RelTraitSet;
import org.eigenbase.reltype.RelDataType;

import com.google.common.collect.Lists;

import cosmos.util.sql.call.Fields;
import cosmos.util.sql.enumerable.EnumerableExpression;
import cosmos.util.sql.enumerable.FieldPacker;
import cosmos.util.sql.impl.CosmosTable;
import cosmos.util.sql.rules.FilterRule;
import cosmos.util.sql.rules.GroupByRule;
import cosmos.util.sql.rules.ProjectRule;
import cosmos.util.sql.rules.LimitRule;

/**
 * Enables the rules to scan a given accumulo table.
 * 
 * @author phrocker
 * 
 */

public class TableScanner extends TableAccessRelBase implements AccumuloRel {
	final CosmosTable resultTable;

	final List<String> fieldList;

	List<String> selectedFields;

	public TableScanner(RelOptCluster cluster, RelTraitSet traitSet,
			RelOptTable table, CosmosTable resultTable, List<String> fieldList) {
		super(cluster, traitSet, table);

		this.resultTable = resultTable;
		this.fieldList = fieldList;
	}

	@Override
	public RelOptPlanWriter explainTerms(RelOptPlanWriter pw) {
		return super.explainTerms(pw);
	}

	@Override
	public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
		assert inputs.isEmpty();
		return this;
	}

	public void setFields(List<String> fields) {
		selectedFields = Lists.newArrayList(fields);
	}

	@Override
	public void register(RelOptPlanner planner) {
		/**
		 * Build the rules.
		 */
		planner.addRule(new FieldPacker(this));
		planner.addRule(new LimitRule(resultTable));
		planner.addRule(EnumerableExpression.ARRAY_INSTANCE);

		planner.addRule(new FilterRule(resultTable));

		planner.addRule(new GroupByRule(resultTable));
		planner.addRule(new ProjectRule(resultTable));
		

	}

	@Override
	public RelDataType deriveRowType() {

		return rowType != null ? rowType : super.deriveRowType();
	}

	@Override
	public RelOptCost computeSelfCost(RelOptPlanner planner) {
		// scans with a small project list are cheaper
		final float f = rowType == null ? 1f
				: (float) rowType.getFieldCount() / 100f;
		return super.computeSelfCost(planner).multiplyBy(.1 * f);
	}

	@Override
	public int implement(Plan implementor) {
		implementor.table = resultTable;

		implementor.add("selectedFields", new Fields(selectedFields));

		return 0;
	}

}
