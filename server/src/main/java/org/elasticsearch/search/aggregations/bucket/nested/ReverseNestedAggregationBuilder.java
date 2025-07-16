/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.aggregations.bucket.nested;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.mapper.NestedObjectMapper;
import org.elasticsearch.index.query.support.NestedScope;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactories.Builder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ReverseNestedAggregationBuilder extends AbstractAggregationBuilder<ReverseNestedAggregationBuilder> {
    public static final String NAME = "reverse_nested";

    private String path;

    public ReverseNestedAggregationBuilder(String name) {
        super(name);
    }

    public ReverseNestedAggregationBuilder(ReverseNestedAggregationBuilder clone, Builder factoriesBuilder, Map<String, Object> map) {
        super(clone, factoriesBuilder, map);
        this.path = clone.path;
    }

    @Override
    protected AggregationBuilder shallowCopy(Builder factoriesBuilder, Map<String, Object> metadata) {
        return new ReverseNestedAggregationBuilder(this, factoriesBuilder, metadata);
    }

    /**
     * Read from a stream.
     */
    public ReverseNestedAggregationBuilder(StreamInput in) throws IOException {
        super(in);
        path = in.readOptionalString();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeOptionalString(path);
    }

    /**
     * Set the path to use for this nested aggregation. The path must match
     * the path to a nested object in the mappings. If it is not specified
     * then this aggregation will go back to the root document.
     */
    public ReverseNestedAggregationBuilder path(String path) {
        if (path == null) {
            throw new IllegalArgumentException("[path] must not be null: [" + name + "]");
        }
        this.path = path;
        return this;
    }

    @Override
    public BucketCardinality bucketCardinality() {
        return BucketCardinality.ONE;
    }

    @Override
    protected AggregatorFactory doBuild(AggregationContext context, AggregatorFactory parent, Builder subFactoriesBuilder)
        throws IOException {
        NestedAggregatorFactory nestedFactory = findNestedAggregatorFactory(parent);
        if (nestedFactory == null) {
            throw new IllegalArgumentException("Reverse nested aggregation [" + name + "] can only be used inside a [nested] aggregation");
        }

        List<ReverseNestedAggregatorFactory> parentReverseNestedFactories = findParentReverseNestedFactories(parent);

        String originalNestedPath = nestedFactory.childObjectMapper != null ? nestedFactory.childObjectMapper.fullPath() : null;
        String effectiveNestedPath = determineEffectiveNestedPath(originalNestedPath, parentReverseNestedFactories);

        if (path != null && isCompatiblePath(path, effectiveNestedPath) == false) {
            throw new IllegalArgumentException(
                "Reverse nested aggregation ["
                    + name
                    + "] with path ["
                    + path
                    + "] is invalid because the effective context is ["
                    + effectiveNestedPath
                    + "]. The path must reference a nested field that is a child of the current context."
            );
        }

        NestedObjectMapper nestedMapper = null;
        if (path != null) {
            nestedMapper = context.nestedLookup().getNestedMappers().get(path);
            if (nestedMapper == null) {
                return new ReverseNestedAggregatorFactory(name, true, null, context, parent, subFactoriesBuilder, metadata);
            }
        }

        NestedScope nestedScope = context.nestedScope();
        try {
            nestedScope.nextLevel(nestedMapper);
            return new ReverseNestedAggregatorFactory(name, false, nestedMapper, context, parent, subFactoriesBuilder, metadata);
        } finally {
            nestedScope.previousLevel();
        }
    }

    private static NestedAggregatorFactory findNestedAggregatorFactory(AggregatorFactory parent) {
        if (parent == null) {
            return null;
        } else if (parent instanceof NestedAggregatorFactory) {
            return (NestedAggregatorFactory) parent;
        } else {
            return findNestedAggregatorFactory(parent.getParent());
        }
    }

    private static String getNestedPathFromFactory(NestedAggregatorFactory factory) {
        if (factory.childObjectMapper != null) {
            return factory.childObjectMapper.fullPath();
        }
        return getNestedPathFromBuilder(factory);
    }

    private static String getNestedPathFromBuilder(AggregatorFactory factory) {
        return null;
    }

    private static List<ReverseNestedAggregatorFactory> findParentReverseNestedFactories(AggregatorFactory parent) {
        List<ReverseNestedAggregatorFactory> factories = new ArrayList<>();
        AggregatorFactory current = parent;
        while (current != null) {
            if (current instanceof ReverseNestedAggregatorFactory) {
                factories.add((ReverseNestedAggregatorFactory) current);
            }
            current = current.getParent();
        }
        return factories;
    }

    private static String determineEffectiveNestedPath(
        String originalNestedPath,
        List<ReverseNestedAggregatorFactory> parentReverseNestedFactories
    ) {
        String effectivePath = originalNestedPath;

        for (int i = parentReverseNestedFactories.size() - 1; i >= 0; i--) {
            ReverseNestedAggregatorFactory factory = parentReverseNestedFactories.get(i);
            if (factory.parentObjectMapper != null) {
                effectivePath = factory.parentObjectMapper.fullPath();
            } else {
                effectivePath = null;
                break;
            }
        }

        return effectivePath;
    }

    private static boolean isCompatiblePath(String targetPath, String effectiveNestedPath) {
        if (targetPath == null) {
            return true;
        }

        if (effectiveNestedPath == null) {
            return targetPath.contains(".") == false;
        }

        return effectiveNestedPath.startsWith(targetPath + ".") || effectiveNestedPath.equals(targetPath);
    }

    @Override
    protected XContentBuilder internalXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (path != null) {
            builder.field(ReverseNestedAggregator.PATH_FIELD.getPreferredName(), path);
        }
        builder.endObject();
        return builder;
    }

    public static ReverseNestedAggregationBuilder parse(String aggregationName, XContentParser parser) throws IOException {
        String path = null;

        XContentParser.Token token;
        String currentFieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.VALUE_STRING) {
                if ("path".equals(currentFieldName)) {
                    path = parser.text();
                } else {
                    throw new ParsingException(
                        parser.getTokenLocation(),
                        "Unknown key for a " + token + " in [" + aggregationName + "]: [" + currentFieldName + "]."
                    );
                }
            } else {
                throw new ParsingException(parser.getTokenLocation(), "Unexpected token " + token + " in [" + aggregationName + "].");
            }
        }

        ReverseNestedAggregationBuilder factory = new ReverseNestedAggregationBuilder(aggregationName);
        if (path != null) {
            factory.path(path);
        }
        return factory;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), path);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        if (super.equals(obj) == false) return false;
        ReverseNestedAggregationBuilder other = (ReverseNestedAggregationBuilder) obj;
        return Objects.equals(path, other.path);
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.ZERO;
    }
}
