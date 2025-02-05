/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.impl;

// ...
// ...
// ...
import static org.jooq.SQLDialect.H2;
import static org.jooq.SQLDialect.HSQLDB;
import static org.jooq.SQLDialect.POSTGRES;
// ...
import static org.jooq.SQLDialect.SQLITE;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.one;
import static org.jooq.impl.Keywords.K_DISTINCT;
import static org.jooq.impl.Keywords.K_FILTER;
import static org.jooq.impl.Keywords.K_ORDER_BY;
import static org.jooq.impl.Keywords.K_WHERE;
import static org.jooq.impl.SQLDataType.DOUBLE;
import static org.jooq.impl.SQLDataType.NUMERIC;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import org.jooq.AggregateFilterStep;
import org.jooq.AggregateFunction;
import org.jooq.ArrayAggOrderByStep;
import org.jooq.Condition;
import org.jooq.Context;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.OrderField;
import org.jooq.OrderedAggregateFunction;
// ...
import org.jooq.QueryPart;
import org.jooq.SQL;
import org.jooq.SQLDialect;
import org.jooq.WindowBeforeOverStep;

/**
 * @author Lukas Eder
 */
abstract class AbstractAggregateFunction<T>
extends AbstractWindowFunction<T>
implements
    AggregateFunction<T>,
    OrderedAggregateFunction<T>,
    ArrayAggOrderByStep<T> {

    /**
     * Generated UID
     */
    private static final long     serialVersionUID     = -8613744948308064895L;
    static final Set<SQLDialect>  SUPPORT_FILTER       = SQLDialect.supportedBy(H2, HSQLDB, POSTGRES, SQLITE);
    static final Set<SQLDialect>  SUPPORT_DISTINCT_RVE = SQLDialect.supportedBy(H2, POSTGRES);

    static final Field<Integer>   ASTERISK             = DSL.field("*", Integer.class);

    // Other attributes
    final QueryPartList<Field<?>> arguments;
    final boolean                 distinct;
    Condition                     filter;

    // Other attributes
    SortFieldList                 withinGroupOrderBy;
    SortFieldList                 keepDenseRankOrderBy;
    boolean                       first;

    AbstractAggregateFunction(boolean distinct, Name name, DataType<T> type, Field<?>... arguments) {
        super(name, type);

        this.distinct = distinct;
        this.arguments = new QueryPartList<>(arguments);
    }

    // -------------------------------------------------------------------------
    // XXX QueryPart API
    // -------------------------------------------------------------------------

    final void acceptArguments0(Context<?> ctx) {
        acceptArguments1(ctx, arguments);
    }

    final void acceptArguments1(Context<?> ctx, QueryPartCollectionView<Field<?>> args) {
        if (distinct) {
            ctx.visit(K_DISTINCT).sql(' ');

            // [#2883][#9109] PostgreSQL and H2 can use the DISTINCT keyword with formal row value expressions.
            if (args.size() > 1 && SUPPORT_DISTINCT_RVE.contains(ctx.dialect()))
                ctx.sql('(');
        }

        if (!args.isEmpty())
            acceptArguments2(ctx, args);

        if (distinct)
            if (args.size() > 1 && SUPPORT_DISTINCT_RVE.contains(ctx.dialect()))
                ctx.sql(')');
    }

    final void acceptArguments2(Context<?> ctx, QueryPartCollectionView<Field<?>> args) {
        if (filter == null || SUPPORT_FILTER.contains(ctx.dialect()))
            ctx.visit(args);
        else
            ctx.visit(args.map(arg -> DSL.when(filter, arg == ASTERISK ? one() : arg)));
    }

    final void acceptFilterClause(Context<?> ctx) {
        acceptFilterClause(ctx, filter);
    }

    static final void acceptFilterClause(Context<?> ctx, Condition filter) {
        if (filter != null && SUPPORT_FILTER.contains(ctx.dialect()))
            ctx.sql(' ')
               .visit(K_FILTER)
               .sql(" (")
               .visit(K_WHERE)
               .sql(' ')
               .visit(filter)
               .sql(')');
    }

    final void acceptOrderBy(Context<?> ctx) {
        if (!Tools.isEmpty(withinGroupOrderBy))
            ctx.sql(' ').visit(K_ORDER_BY).sql(' ')
               .visit(withinGroupOrderBy);
    }

    // -------------------------------------------------------------------------
    // XXX Aggregate function API
    // -------------------------------------------------------------------------

    final QueryPartList<Field<?>> getArguments() {
        return arguments;
    }

    @Override
    public final WindowBeforeOverStep<T> filterWhere(Condition c) {
        filter = c;
        return this;
    }

    @Override
    public final WindowBeforeOverStep<T> filterWhere(Condition... conditions) {
        return filterWhere(Arrays.asList(conditions));
    }

    @Override
    public final WindowBeforeOverStep<T> filterWhere(Collection<? extends Condition> conditions) {
        ConditionProviderImpl c = new ConditionProviderImpl();
        c.addConditions(conditions);
        return filterWhere(c);
    }

    @Override
    public final WindowBeforeOverStep<T> filterWhere(Field<Boolean> field) {
        return filterWhere(condition(field));
    }

    @Override
    public final WindowBeforeOverStep<T> filterWhere(SQL sql) {
        return filterWhere(condition(sql));
    }

    @Override
    public final WindowBeforeOverStep<T> filterWhere(String sql) {
        return filterWhere(condition(sql));
    }

    @Override
    public final WindowBeforeOverStep<T> filterWhere(String sql, Object... bindings) {
        return filterWhere(condition(sql, bindings));
    }

    @Override
    public final WindowBeforeOverStep<T> filterWhere(String sql, QueryPart... parts) {
        return filterWhere(condition(sql, parts));
    }


    @Override
    public final AggregateFunction<T> withinGroupOrderBy(OrderField<?>... fields) {
        return withinGroupOrderBy(Arrays.asList(fields));
    }

    @Override
    public final AggregateFunction<T> withinGroupOrderBy(Collection<? extends OrderField<?>> fields) {
        if (withinGroupOrderBy == null)
            withinGroupOrderBy = new SortFieldList();

        withinGroupOrderBy.addAll(Tools.sortFields(fields));
        return this;
    }





































    @Override
    public /* non-final */ AbstractAggregateFunction<T> orderBy(OrderField<?>... fields) {
        if (windowSpecification != null)
            super.orderBy(fields);
        else
            withinGroupOrderBy(fields);

        return this;
    }

    @Override
    public /* non-final */ AbstractAggregateFunction<T> orderBy(Collection<? extends OrderField<?>> fields) {
        if (windowSpecification != null)
            windowSpecification.orderBy(fields);
        else
            withinGroupOrderBy(fields);

        return this;
    }

    /**
     * Apply this aggregate function's <code>FILTER</code> and <code>OVER</code>
     * clauses to an argument aggregate function.
     */
    final <U> Field<U> fo(AggregateFunction<U> function) {
        WindowBeforeOverStep<U> s1 = filter != null ? function.filterWhere(filter) : function;

        if (windowSpecification != null)
            return s1.over(windowSpecification);
        else if (windowDefinition != null)
            return s1.over(windowDefinition);
        else if (windowName != null)
            return s1.over(windowName);
        else
            return s1;
    }

    /**
     * Type safe <code>NVL2(y, x, null)</code> for statistical function
     * emulations.
     */
    final <U extends Number> Field<U> x(Field<U> x, Field<? extends Number> y) {
        return DSL.nvl2(y, x, DSL.NULL(x.getDataType()));
    }

    /**
     * Type safe <code>NVL2(x, y, null)</code> for statistical function
     * emulations.
     */
    final <U extends Number> Field<U> y(Field<? extends Number> x, Field<U> y) {
        return DSL.nvl2(x, y, DSL.NULL(y.getDataType()));
    }

    /**
     * The data type to use in casts when emulating statistical functions.
     */
    final DataType<? extends Number> d(Context<?> ctx) {
        switch (ctx.family()) {
            case FIREBIRD:
            case HSQLDB:
            case SQLITE:
                return DOUBLE;
            default:
                return NUMERIC;
        }
    }
}
