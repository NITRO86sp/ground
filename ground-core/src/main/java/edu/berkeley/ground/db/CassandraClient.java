/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.berkeley.ground.db;

import com.datastax.driver.core.*;

import edu.berkeley.ground.api.versions.GroundType;
import edu.berkeley.ground.exceptions.EmptyResultException;
import edu.berkeley.ground.exceptions.GroundDBException;
import edu.berkeley.ground.exceptions.GroundException;
import edu.berkeley.ground.util.JGraphTUtils;

import org.jgrapht.*;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class CassandraClient implements DBClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(CassandraClient.class);

  private Cluster cluster;
  private String keyspace;
  private DirectedGraph<String, DefaultEdge> graph;
  private PreparedStatement adjacencyStatement;

  public CassandraClient(String host, int port, String dbName, String username, String password) throws GroundDBException {
    cluster = Cluster.builder()
        .addContactPoint(host)
        .withAuthProvider(new PlainTextAuthProvider(username, password))
        .build();

    this.keyspace = dbName;

    // at startup, load all nodes & edges into JGraphT for later in-memory processing
    ResultSet resultSet = this.cluster.connect(this.keyspace).execute("select id from nodeversions;");
    this.graph = JGraphTUtils.createGraph();

    for (Row r : resultSet.all()) {
      JGraphTUtils.addVertex(graph, r.getString(0));
    }

    resultSet = this.cluster.connect(this.keyspace).execute("select endpoint_one, endpoint_two from edgeversions;");

    for (Row r : resultSet.all()) {
      JGraphTUtils.addEdge(graph, r.getString(0), r.getString(1));
    }

    this.adjacencyStatement = this.cluster.connect(this.keyspace).prepare("select endpoint_two, edge_id from EdgeVersions where endpoint_one = ? allow filtering;");
  }

  public CassandraConnection getConnection() throws GroundDBException {
    return new CassandraConnection(this.cluster.connect(this.keyspace), this.graph, this.adjacencyStatement);
  }

  public class CassandraConnection extends GroundDBConnection {
    private Session session;
    private DirectedGraph<String, DefaultEdge> graph;
    private PreparedStatement adjacencyStatement;

    public CassandraConnection(Session session, DirectedGraph<String, DefaultEdge> graph, PreparedStatement adjacencyStatement) {
      this.session = session;
      this.graph = graph;
      this.adjacencyStatement = adjacencyStatement;
    }

    /**
     * Insert a new row into table with insertValues.
     *
     * @param table        the table to update
     * @param insertValues the values to put into table
     */
    public void insert(String table, List<DbDataContainer> insertValues) {
      // hack to keep JGraphT up to date
      if (table.equals("NodeVersions")) {
        String id = null;
        for (DbDataContainer container : insertValues) {
          if (container.getField().equals("id")) {
            id = container.getValue().toString();
          }
        }

        JGraphTUtils.addVertex(this.graph, id);
      }
      if (table.equals("EdgeVersions")) {
        String nvFromId = null;
        String nvToId = null;

        for (DbDataContainer container : insertValues) {
          if (container.getField().equals("endpoint_one")) {
            nvFromId = container.getValue().toString();
          }

          if (container.getField().equals("endpoint_two")) {
            nvToId = container.getValue().toString();
          }
        }

        JGraphTUtils.addEdge(this.graph, nvFromId, nvToId);
      }

      String insertString = "insert into " + table + "(";
      String valuesString = "values (";

      for (DbDataContainer container : insertValues) {
        insertString += container.getField() + ", ";
        valuesString += "?, ";
      }

      insertString = insertString.substring(0, insertString.length() - 2) + ")";
      valuesString = valuesString.substring(0, valuesString.length() - 2) + ")";

      String prepString = insertString + valuesString + ";";

      BoundStatement statement = new BoundStatement(session.prepare(prepString));

      int index = 0;
      for (DbDataContainer container : insertValues) {
        CassandraClient.setValue(statement, container.getValue(), container.getGroundType(), index);

        index++;
      }

      LOGGER.info("Executing update: " + statement.preparedStatement().getQueryString() + ".");

      this.session.execute(statement);
    }

    /**
     * Retrieve rows based on a set of predicates.
     *
     * @param table               the table to query
     * @param projection          the set of columns to retrieve
     * @param predicatesAndValues the predicates
     */
    public CassandraResults equalitySelect(String table, List<String> projection, List<DbDataContainer> predicatesAndValues) throws EmptyResultException, GroundDBException {
      String select = "select ";
      for (String item : projection) {
        select += item + ", ";
      }

      select = select.substring(0, select.length() - 2) + " from " + table;

      if (predicatesAndValues.size() > 0) {
        select += " where ";

        for (DbDataContainer container : predicatesAndValues) {
          select += container.getField() + " = ? and ";
        }

        select = select.substring(0, select.length() - 4);
      }

      BoundStatement statement = new BoundStatement(this.session.prepare(select + "ALLOW FILTERING;"));

      int index = 0;
      for (DbDataContainer container : predicatesAndValues) {
        CassandraClient.setValue(statement, container.getValue(), container.getGroundType(), index);

        index++;
      }

      LOGGER.info("Executing query: " + statement.preparedStatement().getQueryString() + ".");


      ResultSet resultSet = this.session.execute(statement);

      if (resultSet == null || resultSet.isExhausted()) {
        throw new EmptyResultException("No results found for query: " + statement.toString());
      }

      return new CassandraResults(resultSet);
    }

    public List<String> transitiveClosure(String nodeVersionId) throws GroundException {
      return JGraphTUtils.runDFS(this.graph, nodeVersionId);
    }

    public List<String> adjacentNodes(String nodeVersionId, String edgeNameRegex) throws GroundException {
      BoundStatement statement = new BoundStatement(this.adjacencyStatement);

      statement.setString(0, nodeVersionId);

      ResultSet resultSet = this.session.execute(statement);

      List<String> result = new ArrayList<>();

      for (Row row : resultSet) {
        if (row.getString(1).contains(edgeNameRegex)) {
          result.add(row.getString(0));
        }
      }

      return result;
    }

    public void commit() throws GroundDBException {
      // do nothing; Cassandra doesn't have txns
    }

    public void abort() throws GroundDBException {
      // do nothing; Cassandra doesn't have txns
    }
  }

  private static void setValue(BoundStatement statement, Object value, GroundType groundType, int index) {
    switch (groundType) {
      case STRING:
        if (value != null) {
          statement.setString(index, (String) value);
        } else {
          statement.setToNull(index);
        }

        break;
      case INTEGER:
        if (value != null) {
          statement.setInt(index, (Integer) value);
        } else {
          statement.setToNull(index);
        }
        break;
      case BOOLEAN:
        if (value != null) {
          statement.setBool(index, (Boolean) value);
        } else {
          statement.setToNull(index);
        }
        break;
    }
  }
}
