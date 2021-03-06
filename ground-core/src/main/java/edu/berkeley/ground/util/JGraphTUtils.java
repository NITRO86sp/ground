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

package edu.berkeley.ground.util;

import org.jgrapht.DirectedGraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.DepthFirstIterator;

import java.util.ArrayList;
import java.util.List;

public class JGraphTUtils {
  public static DirectedGraph<String, DefaultEdge> createGraph() {
    return new DefaultDirectedGraph<>(DefaultEdge.class);
  }

  public static void addVertex(DirectedGraph<String, DefaultEdge> graph, String id) {
    graph.addVertex(id);
  }

  public static void addEdge(DirectedGraph<String, DefaultEdge> graph, String from, String to) {
    graph.addEdge(from, to);
  }

  /**
   * Run transitive closure from start.
   *
   * @param graph the graph to query
   * @param start the start version
   * @return the list of reachable nodes
   */
  public static List<String> runDFS(DirectedGraph<String, DefaultEdge> graph, String start) {
    DepthFirstIterator<String, DefaultEdge> iterator = new DepthFirstIterator<>(graph, start);

    List<String> result = new ArrayList<>();

    while (iterator.hasNext()) {
      result.add(iterator.next());
    }

    return result;
  }
}
