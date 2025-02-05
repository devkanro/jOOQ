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
import static org.jooq.SQLDialect.POSTGRES;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.Tools.consumeResultSets;
import static org.jooq.impl.Tools.executeStatementAndGetFirstResultSet;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
// ...

import org.jooq.Configuration;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Record10;
import org.jooq.Record11;
import org.jooq.Record12;
import org.jooq.Record13;
import org.jooq.Record14;
import org.jooq.Record15;
import org.jooq.Record16;
import org.jooq.Record17;
import org.jooq.Record18;
import org.jooq.Record19;
import org.jooq.Record2;
import org.jooq.Record20;
import org.jooq.Record21;
import org.jooq.Record22;
import org.jooq.Record3;
import org.jooq.Record4;
import org.jooq.Record5;
import org.jooq.Record6;
import org.jooq.Record7;
import org.jooq.Record8;
import org.jooq.Record9;
import org.jooq.Result;
import org.jooq.ResultQuery;
import org.jooq.Results;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.conf.SettingsTools;
import org.jooq.tools.JooqLogger;
import org.jooq.tools.jdbc.MockResultSet;

/**
 * A query that returns a {@link Result}
 *
 * @author Lukas Eder
 */
abstract class AbstractResultQuery<R extends Record> extends AbstractQuery<R> implements ResultQuery<R> {

    /**
     * Generated UID
     */
    private static final long                serialVersionUID                  = -5588344253566055707L;
    private static final JooqLogger          log                               = JooqLogger.getLogger(AbstractResultQuery.class);

    private static final Set<SQLDialect>     REPORT_FETCH_SIZE_WITH_AUTOCOMMIT = SQLDialect.supportedBy(POSTGRES);

    private int                              maxRows;
    private int                              fetchSize;
    private int                              resultSetConcurrency;
    private int                              resultSetType;
    private int                              resultSetHoldability;
    private Table<?>                         coerceTable;
    private Collection<? extends Field<?>>   coerceFields;
    private transient boolean                lazy;
    private transient boolean                many;
    private transient Cursor<R>              cursor;
    private transient boolean                autoclosing           = true;
    private Result<R>                        result;
    private ResultsImpl                      results;

    // Some temp variables for String interning
    private final Intern                     intern                = new Intern();

    AbstractResultQuery(Configuration configuration) {
        super(configuration);
    }

    /**
     * Get a list of fields provided a result set.
     */
    protected abstract Field<?>[] getFields(ResultSetMetaData rs) throws SQLException;

    @SuppressWarnings("unchecked")
    @Override
    public final ResultQuery<R> bind(String param, Object value) {
        return (ResultQuery<R>) super.bind(param, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final ResultQuery<R> bind(int index, Object value) {
        return (ResultQuery<R>) super.bind(index, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final ResultQuery<R> poolable(boolean poolable) {
        return (ResultQuery<R>) super.poolable(poolable);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final ResultQuery<R> queryTimeout(int timeout) {
        return (ResultQuery<R>) super.queryTimeout(timeout);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final ResultQuery<R> keepStatement(boolean k) {
        return (ResultQuery<R>) super.keepStatement(k);
    }

    @Override
    public final ResultQuery<R> maxRows(int rows) {
        this.maxRows = rows;
        return this;
    }

    @Override
    public final ResultQuery<R> fetchSize(int rows) {
        this.fetchSize = rows;
        return this;
    }

    @Override
    public final ResultQuery<R> resultSetConcurrency(int concurrency) {
        this.resultSetConcurrency = concurrency;
        return this;
    }

    @Override
    public final ResultQuery<R> resultSetType(int type) {
        this.resultSetType = type;
        return this;
    }

    @Override
    public final ResultQuery<R> resultSetHoldability(int holdability) {
        this.resultSetHoldability = holdability;
        return this;
    }

    @Override
    public final ResultQuery<R> intern(Field<?>... fields) {
        intern.internFields = fields;
        return this;
    }

    @Override
    public final ResultQuery<R> intern(int... fieldIndexes) {
        intern.internIndexes = fieldIndexes;
        return this;
    }

    @Override
    public final ResultQuery<R> intern(String... fieldNameStrings) {
        intern.internNameStrings = fieldNameStrings;
        return this;
    }

    @Override
    public final ResultQuery<R> intern(Name... fieldNames) {
        intern.internNames = fieldNames;
        return this;
    }

    @Override
    protected final void prepare(ExecuteContext ctx) throws SQLException {
        if (ctx.statement() == null) {

            // [#1846] [#2265] [#2299] Users may explicitly specify how ResultSets
            // created by jOOQ behave. This will override any other default behaviour
            if (resultSetConcurrency != 0 || resultSetType != 0 || resultSetHoldability != 0) {
                int type = resultSetType != 0 ? resultSetType : ResultSet.TYPE_FORWARD_ONLY;
                int concurrency = resultSetConcurrency != 0 ? resultSetConcurrency : ResultSet.CONCUR_READ_ONLY;

                // Sybase doesn't support holdability. Avoid setting it!
                if (resultSetHoldability == 0)
                    ctx.statement(ctx.connection().prepareStatement(ctx.sql(), type, concurrency));
                else
                    ctx.statement(ctx.connection().prepareStatement(ctx.sql(), type, concurrency, resultSetHoldability));
            }

            // Regular behaviour
            else
                ctx.statement(ctx.connection().prepareStatement(ctx.sql()));
        }

        Tools.setFetchSize(ctx, fetchSize);

        // [#1854] [#4753] Set the max number of rows for this result query
        int m = SettingsTools.getMaxRows(maxRows, ctx.settings());
        if (m != 0)
            ctx.statement().setMaxRows(m);
    }

    @Override
    protected final int execute(ExecuteContext ctx, ExecuteListener listener) throws SQLException {
        listener.executeStart(ctx);

        // [#4511] [#4753] PostgreSQL doesn't like fetchSize with autoCommit == true
        int f = SettingsTools.getFetchSize(fetchSize, ctx.settings());
        if (REPORT_FETCH_SIZE_WITH_AUTOCOMMIT.contains(ctx.dialect()) && f != 0 && ctx.connection().getAutoCommit())
            log.info("Fetch Size", "A fetch size of " + f + " was set on a auto-commit PostgreSQL connection, which is not recommended. See http://jdbc.postgresql.org/documentation/head/query.html#query-with-cursor");

        SQLException e = executeStatementAndGetFirstResultSet(ctx, rendered.skipUpdateCounts);
        listener.executeEnd(ctx);

        // Fetch a single result set
        notManyIf:
        if (!many) {

            // [#6413] If the first execution yielded an exception, rather than an update count or result set
            //         and that exception is not thrown because of Settings.throwExceptions == THROW_NONE, we can stop
            if (e != null)
                break notManyIf;

            // [#5617] This may happen when using plain SQL API or a MockConnection and expecting a result set where
            //         there is none. The cursor / result is patched into the ctx only for single result sets, where
            //         access to the cursor / result is possible.
            // [#5818] It may also happen in case we're fetching from a batch and the first result is an update count,
            //         not a result set.
            if (ctx.resultSet() == null) {
                DSLContext dsl = DSL.using(ctx.configuration());
                Field<Integer> c = field(name("UPDATE_COUNT"), int.class);
                Result<Record1<Integer>> r = dsl.newResult(c);
                r.add(dsl.newRecord(c).values(ctx.rows()));
                ctx.resultSet(new MockResultSet(r));
            }

            Field<?>[] fields = getFields(ctx.resultSet().getMetaData());
            cursor = new CursorImpl<>(ctx, listener, fields, intern.internIndexes(fields), keepStatement(), keepResultSet(), getRecordType(), SettingsTools.getMaxRows(maxRows, ctx.settings()), autoclosing);

            if (!lazy) {
                result = cursor.fetch();
                cursor = null;
            }
        }

        // Fetch several result sets
        else {
            results = new ResultsImpl(ctx.configuration());
            consumeResultSets(ctx, listener, results, intern, e);
        }

        return result != null ? result.size() : 0;
    }

    @Override
    protected final boolean keepResultSet() {
        return lazy;
    }

    final Collection<? extends Field<?>> coerce() {
        return coerceFields;
    }

    @Override
    public final Result<R> fetch() {
        execute();
        return result;
    }










    @Override
    public final void subscribe(org.reactivestreams.Subscriber<? super R> subscriber) {
        subscriber.onSubscribe(new org.reactivestreams.Subscription() {
            Cursor<R> c;
            ArrayDeque<R> buffer;

            @Override
            public void request(long n) {
                int i = (int) Math.min(n, Integer.MAX_VALUE);

                try {
                    if (c == null)
                        c = fetchLazyNonAutoClosing();

                    if (buffer == null)
                        buffer = new ArrayDeque<>();

                    if (buffer.size() < i)
                        buffer.addAll(c.fetchNext(i - buffer.size()));

                    boolean complete = buffer.size() < i;
                    while (!buffer.isEmpty()) {
                        subscriber.onNext(buffer.pollFirst());
                    }

                    if (complete)
                        doComplete();
                }
                catch (Throwable t) {
                    subscriber.onError(t);
                    doComplete();
                }
            }

            private void doComplete() {
                close();
                subscriber.onComplete();
            }

            private void close() {
                if (c != null)
                    c.close();
            }

            @Override
            public void cancel() {
                close();
            }
        });
    }

    @Override
    public final Cursor<R> fetchLazy() {

        // [#3515] TODO: Avoid modifying a Query's per-execution state
        lazy = true;

        try {
            execute();
        }
        finally {
            lazy = false;
        }

        return cursor;
    }

    /**
     * When we manage the lifecycle of a returned {@link Cursor} internally in
     * jOOQ, then the cursor must not be auto-closed.
     */
    @Override
    final Cursor<R> fetchLazyNonAutoClosing() {
        final boolean previousAutoClosing = autoclosing;

        // [#3515] TODO: Avoid modifying a Query's per-execution state
        autoclosing = false;

        try {
            return fetchLazy();
        }
        finally {
            autoclosing = previousAutoClosing;
        }
    }

    @Override
    public final Results fetchMany() {

        // [#3515] TODO: Avoid modifying a Query's per-execution state
        many = true;

        try {
            execute();
        }
        finally {
            many = false;
        }

        return results;
    }

    @SuppressWarnings("unchecked")
    @Override
    public final Class<? extends R> getRecordType() {
        if (coerceTable != null)
            return (Class<? extends R>) coerceTable.getRecordType();

        return getRecordType0();
    }

    abstract Class<? extends R> getRecordType0();


    @Override
    public final Result<R> getResult() {
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public final <X extends Record> ResultQuery<X> coerce(Table<X> table) {
        this.coerceTable = table;
        return (ResultQuery<X>) coerce(Arrays.asList(table.fields()));
    }

    @Override
    public final ResultQuery<Record> coerce(Field<?>... fields) {
        return coerce(Arrays.asList(fields));
    }

    @SuppressWarnings("unchecked")
    @Override
    public final ResultQuery<Record> coerce(Collection<? extends Field<?>> fields) {
        this.coerceFields = fields;
        return (ResultQuery<Record>) this;
    }



    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1> ResultQuery<Record1<T1>> coerce(Field<T1> field1) {
        return (ResultQuery) coerce(new Field[] { field1 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2> ResultQuery<Record2<T1, T2>> coerce(Field<T1> field1, Field<T2> field2) {
        return (ResultQuery) coerce(new Field[] { field1, field2 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3> ResultQuery<Record3<T1, T2, T3>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4> ResultQuery<Record4<T1, T2, T3, T4>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5> ResultQuery<Record5<T1, T2, T3, T4, T5>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6> ResultQuery<Record6<T1, T2, T3, T4, T5, T6>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7> ResultQuery<Record7<T1, T2, T3, T4, T5, T6, T7>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8> ResultQuery<Record8<T1, T2, T3, T4, T5, T6, T7, T8>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9> ResultQuery<Record9<T1, T2, T3, T4, T5, T6, T7, T8, T9>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> ResultQuery<Record10<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11> ResultQuery<Record11<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12> ResultQuery<Record12<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13> ResultQuery<Record13<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14> ResultQuery<Record14<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15> ResultQuery<Record15<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14, Field<T15> field15) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14, field15 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16> ResultQuery<Record16<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14, Field<T15> field15, Field<T16> field16) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14, field15, field16 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17> ResultQuery<Record17<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14, Field<T15> field15, Field<T16> field16, Field<T17> field17) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14, field15, field16, field17 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18> ResultQuery<Record18<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14, Field<T15> field15, Field<T16> field16, Field<T17> field17, Field<T18> field18) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14, field15, field16, field17, field18 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19> ResultQuery<Record19<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14, Field<T15> field15, Field<T16> field16, Field<T17> field17, Field<T18> field18, Field<T19> field19) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14, field15, field16, field17, field18, field19 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20> ResultQuery<Record20<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14, Field<T15> field15, Field<T16> field16, Field<T17> field17, Field<T18> field18, Field<T19> field19, Field<T20> field20) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14, field15, field16, field17, field18, field19, field20 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21> ResultQuery<Record21<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14, Field<T15> field15, Field<T16> field16, Field<T17> field17, Field<T18> field18, Field<T19> field19, Field<T20> field20, Field<T21> field21) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14, field15, field16, field17, field18, field19, field20, field21 });
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22> ResultQuery<Record22<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22>> coerce(Field<T1> field1, Field<T2> field2, Field<T3> field3, Field<T4> field4, Field<T5> field5, Field<T6> field6, Field<T7> field7, Field<T8> field8, Field<T9> field9, Field<T10> field10, Field<T11> field11, Field<T12> field12, Field<T13> field13, Field<T14> field14, Field<T15> field15, Field<T16> field16, Field<T17> field17, Field<T18> field18, Field<T19> field19, Field<T20> field20, Field<T21> field21, Field<T22> field22) {
        return (ResultQuery) coerce(new Field[] { field1, field2, field3, field4, field5, field6, field7, field8, field9, field10, field11, field12, field13, field14, field15, field16, field17, field18, field19, field20, field21, field22 });
    }


}
