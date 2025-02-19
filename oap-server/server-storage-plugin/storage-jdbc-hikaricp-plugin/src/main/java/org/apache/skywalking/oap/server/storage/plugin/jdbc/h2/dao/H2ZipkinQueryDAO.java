/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.dao;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.skywalking.oap.server.core.storage.query.IZipkinQueryDAO;
import org.apache.skywalking.oap.server.core.zipkin.ZipkinSpanRecord;
import org.apache.skywalking.oap.server.library.client.jdbc.hikaricp.JDBCHikariCPClient;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.library.util.StringUtil;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.storage.QueryRequest;

import static org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.dao.H2TableInstaller.ID_COLUMN;

public class H2ZipkinQueryDAO implements IZipkinQueryDAO {
    private final JDBCHikariCPClient h2Client;
    private final int nameQueryMaxSize = Integer.MAX_VALUE;
    private static final Gson GSON = new Gson();

    public H2ZipkinQueryDAO(JDBCHikariCPClient h2Client) {
        this.h2Client = h2Client;
    }

    @Override
    public List<String> getServiceNames(final long startTimeMillis, final long endTimeMillis) throws IOException {
        return queryNames(ZipkinSpanRecord.LOCAL_ENDPOINT_SERVICE_NAME, startTimeMillis, endTimeMillis, null);
    }

    @Override
    public List<String> getRemoteServiceNames(final long startTimeMillis,
                                              final long endTimeMillis,
                                              final String serviceName) throws IOException {
        return queryNames(ZipkinSpanRecord.REMOTE_ENDPOINT_SERVICE_NAME, startTimeMillis, endTimeMillis, serviceName);
    }

    @Override
    public List<String> getSpanNames(final long startTimeMillis, final long endTimeMillis, final String serviceName) throws IOException {
        return queryNames(ZipkinSpanRecord.NAME, startTimeMillis, endTimeMillis, serviceName);
    }

    @Override
    public List<Span> getTrace(final String traceId) throws IOException {
        StringBuilder sql = new StringBuilder();
        List<Object> condition = new ArrayList<>(1);
        sql.append("select * from ").append(ZipkinSpanRecord.INDEX_NAME);
        sql.append(" where ");
        sql.append(ZipkinSpanRecord.TRACE_ID).append(" = ?");
        condition.add(traceId);
        try (Connection connection = h2Client.getConnection()) {
            ResultSet resultSet = h2Client.executeQuery(connection, sql.toString(), condition.toArray(new Object[0]));
            List<Span> trace = new ArrayList<>();
            while (resultSet.next()) {
                trace.add(buildSpan(resultSet));
            }
            return trace;
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<List<Span>> getTraces(final QueryRequest request) throws IOException {
        final long startTimeMillis = request.endTs() - request.lookback();
        final long endTimeMillis = request.endTs();
        StringBuilder sql = new StringBuilder();
        List<Object> condition = new ArrayList<>(5);
        List<Map.Entry<String, String>> annotations = new ArrayList<>(request.annotationQuery().entrySet());
        sql.append("select distinct ").append(ZipkinSpanRecord.TRACE_ID).append(" from ");
        sql.append(ZipkinSpanRecord.INDEX_NAME);
        /**
         * This is an AdditionalEntity feature, see:
         * {@link org.apache.skywalking.oap.server.core.storage.annotation.SQLDatabase.AdditionalEntity}
         */
        if (!CollectionUtils.isEmpty(annotations)) {
            for (int i = 0; i < annotations.size(); i++) {
                sql.append(" inner join ").append(ZipkinSpanRecord.ADDITIONAL_QUERY_TABLE).append(" ");
                sql.append(ZipkinSpanRecord.ADDITIONAL_QUERY_TABLE + i);
                sql.append(" on ").append(ZipkinSpanRecord.INDEX_NAME).append(".").append(ID_COLUMN).append(" = ");
                sql.append(ZipkinSpanRecord.ADDITIONAL_QUERY_TABLE + i).append(".").append(ID_COLUMN);
            }
        }
        sql.append(" where ");
        sql.append(" 1=1 ");
        if (startTimeMillis > 0 && endTimeMillis > 0) {
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.TIMESTAMP_MILLIS).append(" >= ?");
            condition.add(startTimeMillis);
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.TIMESTAMP_MILLIS).append(" <= ?");
            condition.add(endTimeMillis);
        }
        if (request.minDuration() != null) {
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.DURATION).append(" >= ?");
            condition.add(request.minDuration());
        }
        if (request.maxDuration() != null) {
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.DURATION).append(" <= ?");
            condition.add(request.maxDuration());
        }
        if (!StringUtil.isEmpty(request.serviceName())) {
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.LOCAL_ENDPOINT_SERVICE_NAME).append(" = ?");
            condition.add(request.serviceName());
        }
        if (!StringUtil.isEmpty(request.remoteServiceName())) {
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.REMOTE_ENDPOINT_SERVICE_NAME).append(" = ?");
            condition.add(request.remoteServiceName());
        }
        if (!StringUtil.isEmpty(request.spanName())) {
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.NAME).append(" = ?");
            condition.add(request.spanName());
        }
        if (CollectionUtils.isNotEmpty(annotations)) {
            for (int i = 0; i < annotations.size(); i++) {
                Map.Entry<String, String> annotation = annotations.get(i);
                if (annotation.getValue().isEmpty()) {
                    sql.append(" and ").append(ZipkinSpanRecord.ADDITIONAL_QUERY_TABLE).append(i).append(".");
                    sql.append(ZipkinSpanRecord.QUERY).append(" = ?");
                    condition.add(annotation.getKey());
                } else {
                    sql.append(" and ").append(ZipkinSpanRecord.ADDITIONAL_QUERY_TABLE).append(i).append(".");
                    sql.append(ZipkinSpanRecord.QUERY).append(" = ?");
                    condition.add(annotation.getKey() + "=" + annotation.getValue());
                }
            }
        }
        sql.append(" limit ").append(request.limit());
        Set<String> traceIds = new HashSet<>();
        try (Connection connection = h2Client.getConnection()) {
            ResultSet resultSet = h2Client.executeQuery(connection, sql.toString(), condition.toArray(new Object[0]));
            while (resultSet.next()) {
                traceIds.add(resultSet.getString(ZipkinSpanRecord.TRACE_ID));
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
        return getTraces(traceIds);
    }

    @Override
    public List<List<Span>> getTraces(final Set<String> traceIds) throws IOException {
        if (CollectionUtils.isEmpty(traceIds)) {
            return new ArrayList<>();
        }
        StringBuilder sql = new StringBuilder();
        List<Object> condition = new ArrayList<>(5);
        sql.append("select * from ").append(ZipkinSpanRecord.INDEX_NAME);
        sql.append(" where ");
        sql.append(" 1=1 ");

        int i = 0;
        sql.append(" and ");
        for (final String traceId : traceIds) {
            sql.append(ZipkinSpanRecord.TRACE_ID).append(" = ?");
            condition.add(traceId);
            if (i != traceIds.size() - 1) {
                sql.append(" or ");
            }
            i++;
        }

        try (Connection connection = h2Client.getConnection()) {
            ResultSet resultSet = h2Client.executeQuery(connection, sql.toString(), condition.toArray(new Object[0]));
            return buildTraces(resultSet);
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    private List<String> queryNames(String selectedColumn,
                                    final long startTimeMillis,
                                    final long endTimeMillis,
                                    String serviceName) throws IOException {
        StringBuilder sql = new StringBuilder();
        List<Object> condition = new ArrayList<>(5);
        sql.append("select distinct ").append(selectedColumn).append(" from ");
        sql.append(ZipkinSpanRecord.INDEX_NAME);
        sql.append(" where ");
        sql.append(" 1=1 ");
        if (startTimeMillis > 0 && endTimeMillis > 0) {
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.TIMESTAMP_MILLIS).append(" >= ?");
            condition.add(startTimeMillis);
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.TIMESTAMP_MILLIS).append(" <= ?");
            condition.add(endTimeMillis);
        }
        if (!StringUtil.isEmpty(serviceName)) {
            sql.append(" and ");
            sql.append(ZipkinSpanRecord.LOCAL_ENDPOINT_SERVICE_NAME).append(" = ?");
            condition.add(serviceName);
        }
        sql.append(" and ").append(selectedColumn).append(" is not NULL ");
        sql.append(" limit ").append(nameQueryMaxSize);

        List<String> names = new ArrayList<>();
        try (Connection connection = h2Client.getConnection()) {
            ResultSet resultSet = h2Client.executeQuery(connection, sql.toString(), condition.toArray(new Object[0]));
            while (resultSet.next()) {
                String name = resultSet.getString(selectedColumn);
                names.add(name);
            }
            return names;
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    private List<List<Span>> buildTraces(ResultSet resultSet) throws SQLException {
        Map<String, List<Span>> groupedByTraceId = new LinkedHashMap<String, List<Span>>();
        while (resultSet.next()) {
            Span span = buildSpan(resultSet);
            String traceId = span.traceId();
            groupedByTraceId.putIfAbsent(traceId, new ArrayList<>());
            groupedByTraceId.get(traceId).add(span);
        }
        return new ArrayList<>(groupedByTraceId.values());
    }

    private Span buildSpan(ResultSet resultSet) throws SQLException {
        Span.Builder span = Span.newBuilder();
        span.traceId(resultSet.getString(ZipkinSpanRecord.TRACE_ID));
        span.id(resultSet.getString(ZipkinSpanRecord.SPAN_ID));
        span.parentId(resultSet.getString(ZipkinSpanRecord.PARENT_ID));
        span.kind(Span.Kind.valueOf(resultSet.getString(ZipkinSpanRecord.KIND)));
        span.timestamp(resultSet.getLong(ZipkinSpanRecord.TIMESTAMP));
        span.duration(resultSet.getLong(ZipkinSpanRecord.DURATION));
        span.name(resultSet.getString(ZipkinSpanRecord.NAME));

        if (resultSet.getString(ZipkinSpanRecord.DEBUG) != null) {
            span.debug(Boolean.TRUE);
        }
        if (resultSet.getString(ZipkinSpanRecord.SHARED) != null) {
            span.shared(Boolean.TRUE);
        }
        //Build localEndpoint
        Endpoint.Builder localEndpoint = Endpoint.newBuilder();
        localEndpoint.serviceName(resultSet.getString(ZipkinSpanRecord.LOCAL_ENDPOINT_SERVICE_NAME));
        if (!StringUtil.isEmpty(resultSet.getString(ZipkinSpanRecord.LOCAL_ENDPOINT_IPV4))) {
            localEndpoint.parseIp(resultSet.getString(ZipkinSpanRecord.LOCAL_ENDPOINT_IPV4));
        } else {
            localEndpoint.parseIp(resultSet.getString(ZipkinSpanRecord.LOCAL_ENDPOINT_IPV6));
        }
        localEndpoint.port(resultSet.getInt(ZipkinSpanRecord.LOCAL_ENDPOINT_PORT));
        span.localEndpoint(localEndpoint.build());
        //Build remoteEndpoint
        Endpoint.Builder remoteEndpoint = Endpoint.newBuilder();
        remoteEndpoint.serviceName(resultSet.getString(ZipkinSpanRecord.REMOTE_ENDPOINT_SERVICE_NAME));
        if (!StringUtil.isEmpty(resultSet.getString(ZipkinSpanRecord.REMOTE_ENDPOINT_IPV4))) {
            remoteEndpoint.parseIp(resultSet.getString(ZipkinSpanRecord.REMOTE_ENDPOINT_IPV4));
        } else {
            remoteEndpoint.parseIp(resultSet.getString(ZipkinSpanRecord.REMOTE_ENDPOINT_IPV6));
        }
        remoteEndpoint.port(resultSet.getInt(ZipkinSpanRecord.REMOTE_ENDPOINT_PORT));
        span.remoteEndpoint(remoteEndpoint.build());

        //Build tags
        String tagsString = resultSet.getString(ZipkinSpanRecord.TAGS);
        if (!StringUtil.isEmpty(tagsString)) {
            JsonObject tagsJson = GSON.fromJson(tagsString, JsonObject.class);
            for (Map.Entry<String, JsonElement> tag : tagsJson.entrySet()) {
                span.putTag(tag.getKey(), tag.getValue().getAsString());
            }
        }
        //Build annotation
        String annotationString = resultSet.getString(ZipkinSpanRecord.ANNOTATIONS);
        if (!StringUtil.isEmpty(annotationString)) {
            JsonObject annotationJson = GSON.fromJson(annotationString, JsonObject.class);
            for (Map.Entry<String, JsonElement> annotation : annotationJson.entrySet()) {
                span.addAnnotation(Long.parseLong(annotation.getKey()), annotation.getValue().getAsString());
            }
        }
        return span.build();
    }
}
