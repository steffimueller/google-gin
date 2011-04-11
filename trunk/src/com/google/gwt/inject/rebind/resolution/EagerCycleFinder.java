/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.inject.rebind.resolution;

import com.google.gwt.inject.rebind.ErrorManager;
import com.google.gwt.inject.rebind.binding.Dependency;
import com.google.inject.Inject;
import com.google.inject.Key;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searches for "eager" cycles in the dependency graph.  These are cycles that do not pass through
 * a Provider or AsyncProvider.
 * 
 * <p>This only finds cycles that are necessary to resolve the dependencies for the current origin
 * Ginjector.
 * 
 * <p>Reports errors including the detected cycle and the path that led here from the unresolved
 * bindings in the ginjector to the global {@link ErrorManager}.
 * 
 * <p>See {@link BindingResolver} for how this fits into the overall algorithm for resolution.
 */
public class EagerCycleFinder {
  
  /**
   * For each key that has been visited, this maps to the eager edge that was followed to reach the
   * node, or null if it was used in the initial call to visit.
   */
  private Map<Key<?>, Dependency> visitedEdge;
  
  /**
   * Nodes that are active in the current DFS.  Revisiting any of these nodes indicates an eager
   * cycle, and should be reported as a problem.
   */
  private Set<Key<?>> dfsStack = new HashSet<Key<?>>();
  
  private final ErrorManager errorManager;

  private boolean cycleDetected = false;
  private DependencyGraph graph;
  
  @Inject
  public EagerCycleFinder(ErrorManager errorManager) {
    this.errorManager = errorManager;
  }
  
  /**
   * Detects cycles in the given graph.
   * 
   * @return {@code true} if any cycles were detected
   */
  public boolean findAndReportCycles(DependencyGraph graph) {
    this.graph = graph;
    cycleDetected = false;
    visitedEdge = new HashMap<Key<?>, Dependency>(graph.size());
    
    for (Key<?> key : graph.getAllKeys()) {
      visit(key, null);
    }

    return cycleDetected;
  }
  
  private void visit(Key<?> key, Dependency edge) {
    // If we loop back to a key that is "active" in the current DFS, we have found an eager cycle.
    if (!dfsStack.add(key)) {
      reportCycle(edge);
      return;
    }
    
    // If this is a first time an edge to the target has been visited, we're "discovering" it.
    // We need to recursively walk over the dependencies.
    if (!visitedEdge.containsKey(key)) {
      visitedEdge.put(key, edge);
      for (Dependency nextEdge : graph.getDependenciesOf(key)) {
        if (!nextEdge.isLazy()) {
          visit(nextEdge.getTarget(), nextEdge); // Recursively visit eager edges in the current DFS
        }
      }
    }
    
    dfsStack.remove(key);
  }

  private List<Dependency> describeCycle(Dependency cycleEdge) {
    List<Dependency> cycle = new ArrayList<Dependency>();
    cycle.add(cycleEdge);
    
    Key<?> curr = cycleEdge.getSource();
    while (!curr.equals(cycleEdge.getTarget())) {
      Dependency edge = visitedEdge.get(curr);
      cycle.add(edge);
      curr = edge.getSource();
    }
    Collections.reverse(cycle);
    return cycle;
  }
  
  private void reportCycle(Dependency cycleEdge) {
    // TODO(bchambers, dburrows): Once we have BindingContext on each Dependency, and have a better
    // print method than just dumping the list using toString, update this to report a cleaner
    // error message.
    cycleDetected = true;
    
    // Get the edges in the cycle
    List<Dependency> cycle = describeCycle(cycleEdge);
    
    // Using the edges, determine the keys in the cycle
    PathFinder pathFinder = new PathFinder().onGraph(graph).addRoots(Dependency.GINJECTOR);
    for (Dependency edge : cycle) {
      pathFinder.addDestinations(edge.getTarget());
    }
    
    reportError(pathFinder.findShortestPath(), cycle);
  }
  
  void reportError(List<Dependency> pathToCycle, List<Dependency> cycle) {
    errorManager.logError(String.format("Cycle detected in the dependency graph.  "
        + "Consider using a Provider?%n  Path To Cycle: %s%n  Cycle: %s%n", pathToCycle, cycle));
  }
}