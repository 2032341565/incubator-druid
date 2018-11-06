/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.groupby;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;
import org.apache.druid.data.input.Row;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.query.BaseQuery;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.Queries;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.query.groupby.having.HavingSpec;
import org.apache.druid.query.groupby.orderby.DefaultLimitSpec;
import org.apache.druid.query.groupby.orderby.LimitSpec;
import org.apache.druid.query.groupby.orderby.NoopLimitSpec;
import org.apache.druid.query.groupby.orderby.OrderByColumnSpec;
import org.apache.druid.query.ordering.StringComparator;
import org.apache.druid.query.ordering.StringComparators;
import org.apache.druid.query.spec.LegacySegmentSpec;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.apache.druid.segment.DimensionHandlerUtils;
import org.apache.druid.segment.VirtualColumn;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ValueType;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 */
public class GroupByQuery extends BaseQuery<Row>
{
  public static final String CTX_KEY_SORT_BY_DIMS_FIRST = "sortByDimsFirst";

  private static final Comparator<Row> NON_GRANULAR_TIME_COMP = (Row lhs, Row rhs) -> Longs.compare(
      lhs.getTimestampFromEpoch(),
      rhs.getTimestampFromEpoch()
  );

  public static Builder builder()
  {
    return new Builder();
  }

  private final VirtualColumns virtualColumns;
  private final LimitSpec limitSpec;
  @Nullable
  private final HavingSpec havingSpec;
  @Nullable
  private final DimFilter dimFilter;
  private final List<DimensionSpec> dimensions;
  private final List<AggregatorFactory> aggregatorSpecs;
  private final List<PostAggregator> postAggregatorSpecs;
  @Nullable
  private final List<List<String>> subtotalsSpec;

  private final boolean applyLimitPushDown;
  private final Function<Sequence<Row>, Sequence<Row>> postProcessingFn;

  @JsonCreator
  public GroupByQuery(
      @JsonProperty("dataSource") DataSource dataSource,
      @JsonProperty("intervals") QuerySegmentSpec querySegmentSpec,
      @JsonProperty("virtualColumns") VirtualColumns virtualColumns,
      @JsonProperty("filter") DimFilter dimFilter,
      @JsonProperty("granularity") Granularity granularity,
      @JsonProperty("dimensions") List<DimensionSpec> dimensions,
      @JsonProperty("aggregations") List<AggregatorFactory> aggregatorSpecs,
      @JsonProperty("postAggregations") List<PostAggregator> postAggregatorSpecs,
      @JsonProperty("having") @Nullable HavingSpec havingSpec,
      @JsonProperty("limitSpec") LimitSpec limitSpec,
      @JsonProperty("subtotalsSpec") @Nullable List<List<String>> subtotalsSpec,
      @JsonProperty("context") Map<String, Object> context
  )
  {
    this(
        dataSource,
        querySegmentSpec,
        virtualColumns,
        dimFilter,
        granularity,
        dimensions,
        aggregatorSpecs,
        postAggregatorSpecs,
        havingSpec,
        limitSpec,
        subtotalsSpec,
        null,
        context
    );
  }

  private Function<Sequence<Row>, Sequence<Row>> makePostProcessingFn()
  {
    Function<Sequence<Row>, Sequence<Row>> postProcessingFn = limitSpec.build(
        dimensions,
        aggregatorSpecs,
        postAggregatorSpecs,
        getGranularity(),
        getContextSortByDimsFirst()
    );

    if (havingSpec != null) {
      postProcessingFn = Functions.compose(
          postProcessingFn,
          (Sequence<Row> input) -> {
            havingSpec.setRowSignature(GroupByQueryHelper.rowSignatureFor(GroupByQuery.this));
            havingSpec.setAggregators(getAggregatorsMap(aggregatorSpecs));
            return Sequences.filter(input, havingSpec::eval);
          }
      );
    }
    return postProcessingFn;
  }

  /**
   * A private constructor that avoids recomputing postProcessingFn.
   */
  private GroupByQuery(
      final DataSource dataSource,
      final QuerySegmentSpec querySegmentSpec,
      final VirtualColumns virtualColumns,
      final @Nullable DimFilter dimFilter,
      final Granularity granularity,
      final @Nullable List<DimensionSpec> dimensions,
      final @Nullable List<AggregatorFactory> aggregatorSpecs,
      final @Nullable List<PostAggregator> postAggregatorSpecs,
      final @Nullable HavingSpec havingSpec,
      final LimitSpec limitSpec,
      final @Nullable List<List<String>> subtotalsSpec,
      final @Nullable Function<Sequence<Row>, Sequence<Row>> postProcessingFn,
      final Map<String, Object> context
  )
  {
    super(dataSource, querySegmentSpec, false, context, granularity);

    this.virtualColumns = VirtualColumns.nullToEmpty(virtualColumns);
    this.dimFilter = dimFilter;
    this.dimensions = dimensions == null ? ImmutableList.of() : dimensions;
    for (DimensionSpec spec : this.dimensions) {
      Preconditions.checkArgument(spec != null, "dimensions has null DimensionSpec");
    }

    this.aggregatorSpecs = aggregatorSpecs == null ? ImmutableList.of() : aggregatorSpecs;
    this.postAggregatorSpecs = Queries.prepareAggregations(
        this.dimensions.stream().map(DimensionSpec::getOutputName).collect(Collectors.toList()),
        this.aggregatorSpecs,
        postAggregatorSpecs == null ? ImmutableList.of() : postAggregatorSpecs
    );
    this.havingSpec = havingSpec;
    this.limitSpec = LimitSpec.nullToNoopLimitSpec(limitSpec);

    this.subtotalsSpec = verifySubtotalsSpec(subtotalsSpec, this.dimensions);

    // Verify no duplicate names between dimensions, aggregators, and postAggregators.
    // They will all end up in the same namespace in the returned Rows and we can't have them clobbering each other.
    // We're not counting __time, even though that name is problematic. See: https://github.com/apache/incubator-druid/pull/3684
    verifyOutputNames(this.dimensions, this.aggregatorSpecs, this.postAggregatorSpecs);

    this.postProcessingFn = postProcessingFn != null ? postProcessingFn : makePostProcessingFn();

    // Check if limit push down configuration is valid and check if limit push down will be applied
    this.applyLimitPushDown = determineApplyLimitPushDown();
  }

  @Nullable
  private List<List<String>> verifySubtotalsSpec(
      @Nullable List<List<String>> subtotalsSpec,
      List<DimensionSpec> dimensions
  )
  {
    // if subtotalsSpec exists then validate that all are subsets of dimensions spec and are in same order.
    // For example if we had {D1, D2, D3} in dimensions spec then
    // {D2}, {D1, D2}, {D1, D3}, {D2, D3} etc are valid in subtotalsSpec while
    // {D2, D1} is not as it is not in same order.
    // {D4} is not as its not a subset.
    // This restriction as enforced because implementation does sort merge on the results of top-level query
    // results and expects that ordering of events does not change when dimension columns are removed from
    // results of top level query.
    if (subtotalsSpec != null) {
      for (List<String> subtotalSpec : subtotalsSpec) {
        int i = 0;
        for (String s : subtotalSpec) {
          boolean found = false;
          for (; i < dimensions.size(); i++) {
            if (s.equals(dimensions.get(i).getOutputName())) {
              found = true;
              break;
            }
          }
          if (!found) {
            throw new IAE(
                "Subtotal spec %s is either not a subset or items are in different order than in dimensions.",
                subtotalSpec
            );
          }
        }
      }
    }

    return subtotalsSpec;
  }

  @JsonProperty
  public VirtualColumns getVirtualColumns()
  {
    return virtualColumns;
  }

  @Nullable
  @JsonProperty("filter")
  public DimFilter getDimFilter()
  {
    return dimFilter;
  }

  @JsonProperty
  public List<DimensionSpec> getDimensions()
  {
    return dimensions;
  }

  @JsonProperty("aggregations")
  public List<AggregatorFactory> getAggregatorSpecs()
  {
    return aggregatorSpecs;
  }

  @JsonProperty("postAggregations")
  public List<PostAggregator> getPostAggregatorSpecs()
  {
    return postAggregatorSpecs;
  }

  @JsonProperty("having")
  public HavingSpec getHavingSpec()
  {
    return havingSpec;
  }

  @JsonProperty
  public LimitSpec getLimitSpec()
  {
    return limitSpec;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("subtotalsSpec")
  public List<List<String>> getSubtotalsSpec()
  {
    return subtotalsSpec;
  }

  @Override
  public boolean hasFilters()
  {
    return dimFilter != null;
  }

  @Override
  public DimFilter getFilter()
  {
    return dimFilter;
  }

  @Override
  public String getType()
  {
    return GROUP_BY;
  }

  @JsonIgnore
  public boolean getContextSortByDimsFirst()
  {
    return getContextBoolean(CTX_KEY_SORT_BY_DIMS_FIRST, false);
  }

  @JsonIgnore
  public boolean isApplyLimitPushDown()
  {
    return applyLimitPushDown;
  }

  @JsonIgnore
  public boolean getApplyLimitPushDownFromContext()
  {
    return getContextBoolean(GroupByQueryConfig.CTX_KEY_APPLY_LIMIT_PUSH_DOWN, true);
  }

  @Override
  public Ordering getResultOrdering()
  {
    final Ordering<Row> rowOrdering = getRowOrdering(false);

    return Ordering.from(
        (lhs, rhs) -> {
          if (lhs instanceof Row) {
            return rowOrdering.compare((Row) lhs, (Row) rhs);
          } else {
            // Probably bySegment queries
            return ((Ordering) Comparators.naturalNullsFirst()).compare(lhs, rhs);
          }
        }
    );
  }

  private boolean validateAndGetForceLimitPushDown()
  {
    final boolean forcePushDown = getContextBoolean(GroupByQueryConfig.CTX_KEY_FORCE_LIMIT_PUSH_DOWN, false);
    if (forcePushDown) {
      if (!(limitSpec instanceof DefaultLimitSpec)) {
        throw new IAE("When forcing limit push down, a limit spec must be provided.");
      }

      if (!((DefaultLimitSpec) limitSpec).isLimited()) {
        throw new IAE("When forcing limit push down, the provided limit spec must have a limit.");
      }

      if (havingSpec != null) {
        throw new IAE("Cannot force limit push down when a having spec is present.");
      }

      for (OrderByColumnSpec orderBySpec : ((DefaultLimitSpec) limitSpec).getColumns()) {
        if (OrderByColumnSpec.getPostAggIndexForOrderBy(orderBySpec, postAggregatorSpecs) > -1) {
          throw new UnsupportedOperationException("Limit push down when sorting by a post aggregator is not supported.");
        }
      }
    }
    return forcePushDown;
  }

  public boolean determineApplyLimitPushDown()
  {
    if (subtotalsSpec != null) {
      return false;
    }

    final boolean forceLimitPushDown = validateAndGetForceLimitPushDown();

    if (limitSpec instanceof DefaultLimitSpec) {
      DefaultLimitSpec defaultLimitSpec = (DefaultLimitSpec) limitSpec;

      // If only applying an orderby without a limit, don't try to push down
      if (!defaultLimitSpec.isLimited()) {
        return false;
      }

      if (forceLimitPushDown) {
        return true;
      }

      if (!getApplyLimitPushDownFromContext()) {
        return false;
      }

      if (havingSpec != null) {
        return false;
      }

      // If the sorting order only uses columns in the grouping key, we can always push the limit down
      // to the buffer grouper without affecting result accuracy
      boolean sortHasNonGroupingFields = DefaultLimitSpec.sortingOrderHasNonGroupingFields(
          (DefaultLimitSpec) limitSpec,
          getDimensions()
      );

      return !sortHasNonGroupingFields;
    }

    return false;
  }

  /**
   * When limit push down is applied, the partial results would be sorted by the ordering specified by the
   * limit/order spec (unlike non-push down case where the results always use the default natural ascending order),
   * so when merging these partial result streams, the merge needs to use the same ordering to get correct results.
   */
  private Ordering<Row> getRowOrderingForPushDown(
      final boolean granular,
      final DefaultLimitSpec limitSpec
  )
  {
    final boolean sortByDimsFirst = getContextSortByDimsFirst();

    final List<String> orderedFieldNames = new ArrayList<>();
    final Set<Integer> dimsInOrderBy = new HashSet<>();
    final List<Boolean> needsReverseList = new ArrayList<>();
    final List<ValueType> dimensionTypes = new ArrayList<>();
    final List<StringComparator> comparators = new ArrayList<>();

    for (OrderByColumnSpec orderSpec : limitSpec.getColumns()) {
      boolean needsReverse = orderSpec.getDirection() != OrderByColumnSpec.Direction.ASCENDING;
      int dimIndex = OrderByColumnSpec.getDimIndexForOrderBy(orderSpec, dimensions);
      if (dimIndex >= 0) {
        DimensionSpec dim = dimensions.get(dimIndex);
        orderedFieldNames.add(dim.getOutputName());
        dimsInOrderBy.add(dimIndex);
        needsReverseList.add(needsReverse);
        final ValueType type = dimensions.get(dimIndex).getOutputType();
        dimensionTypes.add(type);
        comparators.add(orderSpec.getDimensionComparator());
      }
    }

    for (int i = 0; i < dimensions.size(); i++) {
      if (!dimsInOrderBy.contains(i)) {
        orderedFieldNames.add(dimensions.get(i).getOutputName());
        needsReverseList.add(false);
        final ValueType type = dimensions.get(i).getOutputType();
        dimensionTypes.add(type);
        comparators.add(StringComparators.LEXICOGRAPHIC);
      }
    }

    final Comparator<Row> timeComparator = getTimeComparator(granular);

    if (timeComparator == null) {
      return Ordering.from(
          new Comparator<Row>()
          {
            @Override
            public int compare(Row lhs, Row rhs)
            {
              return compareDimsForLimitPushDown(
                  orderedFieldNames,
                  needsReverseList,
                  dimensionTypes,
                  comparators,
                  lhs,
                  rhs
              );
            }
          }
      );
    } else if (sortByDimsFirst) {
      return Ordering.from(
          new Comparator<Row>()
          {
            @Override
            public int compare(Row lhs, Row rhs)
            {
              final int cmp = compareDimsForLimitPushDown(
                  orderedFieldNames,
                  needsReverseList,
                  dimensionTypes,
                  comparators,
                  lhs,
                  rhs
              );
              if (cmp != 0) {
                return cmp;
              }

              return timeComparator.compare(lhs, rhs);
            }
          }
      );
    } else {
      return Ordering.from(
          new Comparator<Row>()
          {
            @Override
            public int compare(Row lhs, Row rhs)
            {
              final int timeCompare = timeComparator.compare(lhs, rhs);

              if (timeCompare != 0) {
                return timeCompare;
              }

              return compareDimsForLimitPushDown(
                  orderedFieldNames,
                  needsReverseList,
                  dimensionTypes,
                  comparators,
                  lhs,
                  rhs
              );
            }
          }
      );
    }
  }

  public Ordering<Row> getRowOrdering(final boolean granular)
  {
    if (applyLimitPushDown) {
      if (!DefaultLimitSpec.sortingOrderHasNonGroupingFields((DefaultLimitSpec) limitSpec, dimensions)) {
        return getRowOrderingForPushDown(granular, (DefaultLimitSpec) limitSpec);
      }
    }

    final boolean sortByDimsFirst = getContextSortByDimsFirst();
    final Comparator<Row> timeComparator = getTimeComparator(granular);

    if (timeComparator == null) {
      return Ordering.from((lhs, rhs) -> compareDims(dimensions, lhs, rhs));
    } else if (sortByDimsFirst) {
      return Ordering.from(
          (lhs, rhs) -> {
            final int cmp = compareDims(dimensions, lhs, rhs);
            if (cmp != 0) {
              return cmp;
            }

            return timeComparator.compare(lhs, rhs);
          }
      );
    } else {
      return Ordering.from(
          (lhs, rhs) -> {
            final int timeCompare = timeComparator.compare(lhs, rhs);

            if (timeCompare != 0) {
              return timeCompare;
            }

            return compareDims(dimensions, lhs, rhs);
          }
      );
    }
  }

  @Nullable
  private Comparator<Row> getTimeComparator(boolean granular)
  {
    if (Granularities.ALL.equals(getGranularity())) {
      return null;
    } else if (granular) {
      return (lhs, rhs) -> Longs.compare(
          getGranularity().bucketStart(lhs.getTimestamp()).getMillis(),
          getGranularity().bucketStart(rhs.getTimestamp()).getMillis()
      );
    } else {
      return NON_GRANULAR_TIME_COMP;
    }
  }

  private static int compareDims(List<DimensionSpec> dimensions, Row lhs, Row rhs)
  {
    for (DimensionSpec dimension : dimensions) {
      //noinspection unchecked
      final int dimCompare = DimensionHandlerUtils.compareObjectsAsType(
          lhs.getRaw(dimension.getOutputName()),
          rhs.getRaw(dimension.getOutputName()),
          dimension.getOutputType()
      );
      if (dimCompare != 0) {
        return dimCompare;
      }
    }

    return 0;
  }

  private static int compareDimsForLimitPushDown(
      final List<String> fields,
      final List<Boolean> needsReverseList,
      final List<ValueType> dimensionTypes,
      final List<StringComparator> comparators,
      Row lhs,
      Row rhs
  )
  {
    for (int i = 0; i < fields.size(); i++) {
      final String fieldName = fields.get(i);
      final StringComparator comparator = comparators.get(i);
      final ValueType dimensionType = dimensionTypes.get(i);

      final int dimCompare;
      final Object lhsObj = lhs.getRaw(fieldName);
      final Object rhsObj = rhs.getRaw(fieldName);

      if (ValueType.isNumeric(dimensionType)) {
        if (comparator.equals(StringComparators.NUMERIC)) {
          dimCompare = DimensionHandlerUtils.compareObjectsAsType(lhsObj, rhsObj, dimensionType);
        } else {
          dimCompare = comparator.compare(String.valueOf(lhsObj), String.valueOf(rhsObj));
        }
      } else {
        dimCompare = comparator.compare((String) lhsObj, (String) rhsObj);
      }

      if (dimCompare != 0) {
        return needsReverseList.get(i) ? -dimCompare : dimCompare;
      }
    }
    return 0;
  }

  /**
   * Apply the havingSpec and limitSpec. Because havingSpecs are not thread safe, and because they are applied during
   * accumulation of the returned sequence, callers must take care to avoid accumulating two different Sequences
   * returned by this method in two different threads.
   *
   * @param results sequence of rows to apply havingSpec and limitSpec to
   *
   * @return sequence of rows after applying havingSpec and limitSpec
   */
  public Sequence<Row> postProcess(Sequence<Row> results)
  {
    return postProcessingFn.apply(results);
  }

  @Override
  public GroupByQuery withOverriddenContext(Map<String, Object> contextOverride)
  {
    return new Builder(this).overrideContext(contextOverride).build();
  }

  @Override
  public GroupByQuery withQuerySegmentSpec(QuerySegmentSpec spec)
  {
    return new Builder(this).setQuerySegmentSpec(spec).build();
  }

  public GroupByQuery withDimFilter(final DimFilter dimFilter)
  {
    return new Builder(this).setDimFilter(dimFilter).build();
  }

  @Override
  public Query<Row> withDataSource(DataSource dataSource)
  {
    return new Builder(this).setDataSource(dataSource).build();
  }

  public GroupByQuery withDimensionSpecs(final List<DimensionSpec> dimensionSpecs)
  {
    return new Builder(this).setDimensions(dimensionSpecs).build();
  }

  public GroupByQuery withLimitSpec(LimitSpec limitSpec)
  {
    return new Builder(this).setLimitSpec(limitSpec).build();
  }

  public GroupByQuery withAggregatorSpecs(final List<AggregatorFactory> aggregatorSpecs)
  {
    return new Builder(this).setAggregatorSpecs(aggregatorSpecs).build();
  }

  public GroupByQuery withSubtotalsSpec(final List<List<String>> subtotalsSpec)
  {
    return new Builder(this).setSubtotalsSpec(subtotalsSpec).build();
  }

  public GroupByQuery withPostAggregatorSpecs(final List<PostAggregator> postAggregatorSpecs)
  {
    return new Builder(this).setPostAggregatorSpecs(postAggregatorSpecs).build();
  }

  private static void verifyOutputNames(
      List<DimensionSpec> dimensions,
      List<AggregatorFactory> aggregators,
      List<PostAggregator> postAggregators
  )
  {
    final Set<String> outputNames = new HashSet<>();
    for (DimensionSpec dimension : dimensions) {
      if (!outputNames.add(dimension.getOutputName())) {
        throw new IAE("Duplicate output name[%s]", dimension.getOutputName());
      }
    }

    for (AggregatorFactory aggregator : aggregators) {
      if (!outputNames.add(aggregator.getName())) {
        throw new IAE("Duplicate output name[%s]", aggregator.getName());
      }
    }

    for (PostAggregator postAggregator : postAggregators) {
      if (!outputNames.add(postAggregator.getName())) {
        throw new IAE("Duplicate output name[%s]", postAggregator.getName());
      }
    }

    if (outputNames.contains(ColumnHolder.TIME_COLUMN_NAME)) {
      throw new IAE(
          "'%s' cannot be used as an output name for dimensions, aggregators, or post-aggregators.",
          ColumnHolder.TIME_COLUMN_NAME
      );
    }
  }

  private static Map<String, AggregatorFactory> getAggregatorsMap(List<AggregatorFactory> aggregatorSpecs)
  {
    Map<String, AggregatorFactory> map = new HashMap<>(aggregatorSpecs.size());
    aggregatorSpecs.stream().forEach(v -> map.put(v.getName(), v));
    return map;
  }

  public static class Builder
  {
    @Nullable
    private static List<List<String>> copySubtotalSpec(@Nullable List<List<String>> subtotalsSpec)
    {
      if (subtotalsSpec == null) {
        return null;
      }
      return subtotalsSpec.stream().map(ArrayList::new).collect(Collectors.toList());
    }

    private DataSource dataSource;
    private QuerySegmentSpec querySegmentSpec;
    private VirtualColumns virtualColumns;
    @Nullable
    private DimFilter dimFilter;
    private Granularity granularity;
    @Nullable
    private List<DimensionSpec> dimensions;
    @Nullable
    private List<AggregatorFactory> aggregatorSpecs;
    @Nullable
    private List<PostAggregator> postAggregatorSpecs;
    @Nullable
    private HavingSpec havingSpec;

    private Map<String, Object> context;

    @Nullable
    private List<List<String>> subtotalsSpec = null;
    @Nullable
    private LimitSpec limitSpec = null;
    @Nullable
    private Function<Sequence<Row>, Sequence<Row>> postProcessingFn;
    private List<OrderByColumnSpec> orderByColumnSpecs = new ArrayList<>();
    private int limit = Integer.MAX_VALUE;

    public Builder()
    {
    }

    public Builder(GroupByQuery query)
    {
      dataSource = query.getDataSource();
      querySegmentSpec = query.getQuerySegmentSpec();
      virtualColumns = query.getVirtualColumns();
      dimFilter = query.getDimFilter();
      granularity = query.getGranularity();
      dimensions = query.getDimensions();
      aggregatorSpecs = query.getAggregatorSpecs();
      postAggregatorSpecs = query.getPostAggregatorSpecs();
      havingSpec = query.getHavingSpec();
      limitSpec = query.getLimitSpec();
      subtotalsSpec = query.subtotalsSpec;
      postProcessingFn = query.postProcessingFn;
      context = query.getContext();
    }

    public Builder(Builder builder)
    {
      dataSource = builder.dataSource;
      querySegmentSpec = builder.querySegmentSpec;
      virtualColumns = builder.virtualColumns;
      dimFilter = builder.dimFilter;
      granularity = builder.granularity;
      dimensions = builder.dimensions;
      aggregatorSpecs = builder.aggregatorSpecs;
      postAggregatorSpecs = builder.postAggregatorSpecs;
      havingSpec = builder.havingSpec;
      limitSpec = builder.limitSpec;
      subtotalsSpec = copySubtotalSpec(builder.subtotalsSpec);
      postProcessingFn = builder.postProcessingFn;
      limit = builder.limit;
      orderByColumnSpecs = new ArrayList<>(builder.orderByColumnSpecs);
      context = builder.context;
    }

    public Builder setDataSource(DataSource dataSource)
    {
      this.dataSource = dataSource;
      return this;
    }

    public Builder setDataSource(String dataSource)
    {
      this.dataSource = new TableDataSource(dataSource);
      return this;
    }

    public Builder setDataSource(Query query)
    {
      this.dataSource = new QueryDataSource(query);
      return this;
    }

    public Builder setInterval(QuerySegmentSpec interval)
    {
      return setQuerySegmentSpec(interval);
    }

    public Builder setInterval(List<Interval> intervals)
    {
      return setQuerySegmentSpec(new LegacySegmentSpec(intervals));
    }

    public Builder setInterval(Interval interval)
    {
      return setQuerySegmentSpec(new LegacySegmentSpec(interval));
    }

    public Builder setInterval(String interval)
    {
      return setQuerySegmentSpec(new LegacySegmentSpec(interval));
    }

    public Builder setVirtualColumns(VirtualColumns virtualColumns)
    {
      this.virtualColumns = Preconditions.checkNotNull(virtualColumns, "virtualColumns");
      return this;
    }

    public Builder setVirtualColumns(VirtualColumn... virtualColumns)
    {
      this.virtualColumns = VirtualColumns.create(Arrays.asList(virtualColumns));
      return this;
    }

    public Builder setLimit(int limit)
    {
      ensureExplicitLimitSpecNotSet();
      this.limit = limit;
      this.postProcessingFn = null;
      return this;
    }

    public Builder setSubtotalsSpec(List<List<String>> subtotalsSpec)
    {
      this.subtotalsSpec = subtotalsSpec;
      return this;
    }

    public Builder addOrderByColumn(String dimension)
    {
      return addOrderByColumn(dimension, null);
    }

    public Builder addOrderByColumn(String dimension, OrderByColumnSpec.Direction direction)
    {
      return addOrderByColumn(new OrderByColumnSpec(dimension, direction));
    }

    public Builder addOrderByColumn(OrderByColumnSpec columnSpec)
    {
      ensureExplicitLimitSpecNotSet();
      this.orderByColumnSpecs.add(columnSpec);
      this.postProcessingFn = null;
      return this;
    }

    public Builder setLimitSpec(LimitSpec limitSpec)
    {
      Preconditions.checkNotNull(limitSpec);
      ensureFluentLimitsNotSet();
      this.limitSpec = limitSpec;
      this.postProcessingFn = null;
      return this;
    }

    private void ensureExplicitLimitSpecNotSet()
    {
      if (limitSpec != null) {
        throw new ISE("Ambiguous build, limitSpec[%s] already set", limitSpec);
      }
    }

    private void ensureFluentLimitsNotSet()
    {
      if (!(limit == Integer.MAX_VALUE && orderByColumnSpecs.isEmpty())) {
        throw new ISE("Ambiguous build, limit[%s] or columnSpecs[%s] already set.", limit, orderByColumnSpecs);
      }
    }

    public Builder setQuerySegmentSpec(QuerySegmentSpec querySegmentSpec)
    {
      this.querySegmentSpec = querySegmentSpec;
      return this;
    }

    public Builder setDimFilter(DimFilter dimFilter)
    {
      this.dimFilter = dimFilter;
      return this;
    }

    public Builder setGranularity(Granularity granularity)
    {
      this.granularity = granularity;
      return this;
    }

    public Builder addDimension(String column)
    {
      return addDimension(column, column);
    }

    public Builder addDimension(String column, String outputName)
    {
      return addDimension(new DefaultDimensionSpec(column, outputName));
    }

    public Builder addDimension(DimensionSpec dimension)
    {
      if (dimensions == null) {
        dimensions = new ArrayList<>();
      }

      dimensions.add(dimension);
      this.postProcessingFn = null;
      return this;
    }

    public Builder setDimensions(List<DimensionSpec> dimensions)
    {
      this.dimensions = Lists.newArrayList(dimensions);
      this.postProcessingFn = null;
      return this;
    }

    public Builder setDimensions(DimensionSpec... dimensions)
    {
      this.dimensions = new ArrayList<>(Arrays.asList(dimensions));
      this.postProcessingFn = null;
      return this;
    }

    public Builder addAggregator(AggregatorFactory aggregator)
    {
      if (aggregatorSpecs == null) {
        aggregatorSpecs = new ArrayList<>();
      }

      aggregatorSpecs.add(aggregator);
      this.postProcessingFn = null;
      return this;
    }

    public Builder setAggregatorSpecs(List<AggregatorFactory> aggregatorSpecs)
    {
      this.aggregatorSpecs = Lists.newArrayList(aggregatorSpecs);
      this.postProcessingFn = null;
      return this;
    }

    public Builder setAggregatorSpecs(AggregatorFactory... aggregatorSpecs)
    {
      this.aggregatorSpecs = new ArrayList<>(Arrays.asList(aggregatorSpecs));
      this.postProcessingFn = null;
      return this;
    }

    public Builder setPostAggregatorSpecs(List<PostAggregator> postAggregatorSpecs)
    {
      this.postAggregatorSpecs = Lists.newArrayList(postAggregatorSpecs);
      this.postProcessingFn = null;
      return this;
    }

    public Builder setContext(Map<String, Object> context)
    {
      this.context = context;
      return this;
    }

    public Builder overrideContext(Map<String, Object> contextOverride)
    {
      this.context = computeOverriddenContext(context, contextOverride);
      return this;
    }

    public Builder setHavingSpec(HavingSpec havingSpec)
    {
      this.havingSpec = havingSpec;
      this.postProcessingFn = null;
      return this;
    }

    public Builder copy()
    {
      return new Builder(this);
    }

    public GroupByQuery build()
    {
      final LimitSpec theLimitSpec;
      if (limitSpec == null) {
        if (orderByColumnSpecs.isEmpty() && limit == Integer.MAX_VALUE) {
          theLimitSpec = NoopLimitSpec.instance();
        } else {
          theLimitSpec = new DefaultLimitSpec(orderByColumnSpecs, limit);
        }
      } else {
        theLimitSpec = limitSpec;
      }

      return new GroupByQuery(
          dataSource,
          querySegmentSpec,
          virtualColumns,
          dimFilter,
          granularity,
          dimensions,
          aggregatorSpecs,
          postAggregatorSpecs,
          havingSpec,
          theLimitSpec,
          subtotalsSpec,
          postProcessingFn,
          context
      );
    }
  }

  @Override
  public String toString()
  {
    return "GroupByQuery{" +
           "dataSource='" + getDataSource() + '\'' +
           ", querySegmentSpec=" + getQuerySegmentSpec() +
           ", virtualColumns=" + virtualColumns +
           ", limitSpec=" + limitSpec +
           ", dimFilter=" + dimFilter +
           ", granularity=" + getGranularity() +
           ", dimensions=" + dimensions +
           ", aggregatorSpecs=" + aggregatorSpecs +
           ", postAggregatorSpecs=" + postAggregatorSpecs +
           ", havingSpec=" + havingSpec +
           '}';
  }

  @Override
  public boolean equals(final Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    final GroupByQuery that = (GroupByQuery) o;
    return Objects.equals(virtualColumns, that.virtualColumns) &&
           Objects.equals(limitSpec, that.limitSpec) &&
           Objects.equals(havingSpec, that.havingSpec) &&
           Objects.equals(dimFilter, that.dimFilter) &&
           Objects.equals(dimensions, that.dimensions) &&
           Objects.equals(aggregatorSpecs, that.aggregatorSpecs) &&
           Objects.equals(postAggregatorSpecs, that.postAggregatorSpecs);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(
        super.hashCode(),
        virtualColumns,
        limitSpec,
        havingSpec,
        dimFilter,
        dimensions,
        aggregatorSpecs,
        postAggregatorSpecs
    );
  }
}