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

package edu.berkeley.ground.api.models.cassandra;

import edu.berkeley.ground.api.models.StructureVersion;
import edu.berkeley.ground.api.models.StructureVersionFactory;
import edu.berkeley.ground.api.versions.GroundType;
import edu.berkeley.ground.api.versions.cassandra.CassandraVersionFactory;
import edu.berkeley.ground.db.CassandraClient;
import edu.berkeley.ground.db.CassandraClient.CassandraConnection;
import edu.berkeley.ground.db.DBClient;
import edu.berkeley.ground.db.DbDataContainer;
import edu.berkeley.ground.db.QueryResults;
import edu.berkeley.ground.exceptions.EmptyResultException;
import edu.berkeley.ground.exceptions.GroundException;
import edu.berkeley.ground.util.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CassandraStructureVersionFactory extends StructureVersionFactory {
  private static final Logger LOGGER = LoggerFactory.getLogger(CassandraStructureVersionFactory.class);
  private CassandraClient dbClient;

  private CassandraStructureFactory structureFactory;
  private CassandraVersionFactory versionFactory;

  public CassandraStructureVersionFactory(CassandraStructureFactory structureFactory, CassandraVersionFactory versionFactory, CassandraClient dbClient) {
    this.dbClient = dbClient;
    this.structureFactory = structureFactory;
    this.versionFactory = versionFactory;
  }

  public StructureVersion create(String structureId,
                                 Map<String, GroundType> attributes,
                                 List<String> parentIds) throws GroundException {

    CassandraConnection connection = this.dbClient.getConnection();
    String id = IdGenerator.generateId(structureId);

    this.versionFactory.insertIntoDatabase(connection, id);

    List<DbDataContainer> insertions = new ArrayList<>();
    insertions.add(new DbDataContainer("id", GroundType.STRING, id));
    insertions.add(new DbDataContainer("structure_id", GroundType.STRING, structureId));

    connection.insert("StructureVersions", insertions);

    for (String key : attributes.keySet()) {
      List<DbDataContainer> itemInsertions = new ArrayList<>();
      itemInsertions.add(new DbDataContainer("svid", GroundType.STRING, id));
      itemInsertions.add(new DbDataContainer("key", GroundType.STRING, key));
      itemInsertions.add(new DbDataContainer("type", GroundType.STRING, attributes.get(key).toString()));

      connection.insert("StructureVersionItems", itemInsertions);
    }

    this.structureFactory.update(connection, structureId, id, parentIds);

    connection.commit();
    LOGGER.info("Created structure version " + id + " in structure " + structureId + ".");

    return StructureVersionFactory.construct(id, structureId, attributes);
  }

  public StructureVersion retrieveFromDatabase(String id) throws GroundException {
    CassandraConnection connection = this.dbClient.getConnection();

    List<DbDataContainer> predicates = new ArrayList<>();
    predicates.add(new DbDataContainer("id", GroundType.STRING, id));

    QueryResults resultSet;
    try {
      resultSet = connection.equalitySelect("StructureVersions", DBClient.SELECT_STAR, predicates);
    } catch (EmptyResultException eer) {
      throw new GroundException("No StructureVersion found with id " + id + ".");
    }

    if (!resultSet.next()) {
      throw new GroundException("No StructureVersion found with id " + id + ".");
    }

    Map<String, GroundType> attributes = new HashMap<>();

    try {
      List<DbDataContainer> attributePredicates = new ArrayList<>();
      attributePredicates.add(new DbDataContainer("svid", GroundType.STRING, id));
      QueryResults attributesSet = connection.equalitySelect("StructureVersionItems", DBClient.SELECT_STAR, attributePredicates);

      while (attributesSet.next()) {
        attributes.put(attributesSet.getString(1), GroundType.fromString(attributesSet.getString(2)));
      }
    } catch (EmptyResultException eer) {
      throw new GroundException("No StructureVersion attributes found for id " + id + ".");
    }

    String structureId = resultSet.getString(1);

    connection.commit();
    LOGGER.info("Retrieved structure version " + id + " in structure " + structureId + ".");

    return StructureVersionFactory.construct(id, structureId, attributes);
  }
}
