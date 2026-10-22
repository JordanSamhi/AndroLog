'use strict';
const assert = require('assert');
const graph = require('../../main/resources/report-graph.js');

function node(id, inReport = true) { return { id, className: id.split(':')[0], label: id, inReport }; }
function edge(caller, callee, kind = 'observed', count = 1, callSites = []) { return { caller, callee, kind, count, callSites }; }

// Runtime-confirmed and declared relationships between the same methods remain
// distinct, while repeated records of one relationship aggregate their counts.
const input = {
  nodes: [node('A'), node('B', false), node('__proto__'), node('<evil>')],
  edges: [edge('A', 'B', 'observed', 2), edge('A', 'B', 'observed', 3, [{ id: 'site', declared: 'B', kind: 'virtual' }]),
    edge('A', 'B', 'declared', 4), edge('B', 'A'), edge('A', 'A'), edge('missing', 'A'), edge('A', '__proto__')]
};
const original = JSON.stringify(input);
const index = graph.createIndex(input);
assert.strictEqual(JSON.stringify(input), original, 'Indexing must not mutate report data');
assert.strictEqual(index.nodes.length, 4);
assert.strictEqual(index.edges.length, 5);
assert.strictEqual(index.byId.get('B').inReport, false);
assert.strictEqual(index.byId.get('__proto__').id, '__proto__');
const observed = index.edges.find(e => e.caller === 'A' && e.callee === 'B' && e.kind === 'observed');
assert.strictEqual(observed.count, 5);
assert.strictEqual(observed.callSites[0].id, 'site');
assert.strictEqual(index.edges.find(e => e.kind === 'declared').count, 4);
assert.strictEqual(index.incident.get('A').length, 5, 'Self-calls are present once in incident adjacency');
assert.strictEqual(index.outgoing.get('A').filter(e => e.callee === 'A').length, 1);

// Neighborhoods include callers as well as callees, preserve cycles and
// self-calls, and stop at the selected graph distance.
const chain = graph.createIndex({ nodes: ['A', 'B', 'C', 'D', 'E', 'F'].map(id => node(id)), edges: [edge('A', 'B'), edge('B', 'C'), edge('C', 'D'), edge('E', 'A'), edge('A', 'A'), edge('B', 'A')] });
const one = graph.selectNeighborhood(chain, 'A', { depth: 1 });
assert.deepStrictEqual(new Set(one.nodes.map(n => n.id)), new Set(['A', 'B', 'E']));
assert(one.edges.some(e => e.caller === e.callee));
assert(one.edges.some(e => e.caller === 'B' && e.callee === 'A'));
const two = graph.selectNeighborhood(chain, 'A', { depth: 2 });
assert.deepStrictEqual(new Set(two.nodes.map(n => n.id)), new Set(['A', 'B', 'C', 'E']));
assert.strictEqual(two.levels.get('C'), 2);
assert.strictEqual(two.levels.get('E'), -1);
assert.strictEqual(graph.selectNeighborhood(chain, 'missing').nodes.length, 0);

// Selecting a method includes only its own incoming/outgoing relationships.
// A triangle must not leak the edge between its two neighboring methods, nor
// their self-calls, while selected self-calls and both directions/kinds survive.
const triangle = graph.createIndex({
  nodes: ['A', 'B', 'C', 'D'].map(id => node(id)),
  edges: [edge('A', 'B'), edge('A', 'B', 'declared'), edge('B', 'A'), edge('A', 'C'),
    edge('A', 'A'), edge('A', 'A', 'declared'), edge('B', 'C'), edge('C', 'B', 'declared'),
    edge('B', 'B'), edge('C', 'C', 'declared'), edge('C', 'D')]
});
const incident = graph.selectNeighborhood(triangle, 'A', { incidentOnly: true, depth: 2 });
assert.deepStrictEqual(new Set(incident.nodes.map(n => n.id)), new Set(['A', 'B', 'C']), 'Selected mode never includes second-hop methods');
assert.deepStrictEqual(new Set(incident.edges.map(e => JSON.stringify([e.caller, e.callee, e.kind]))), new Set([
  ['A', 'B', 'observed'], ['A', 'B', 'declared'], ['B', 'A', 'observed'], ['A', 'C', 'observed'], ['A', 'A', 'observed'], ['A', 'A', 'declared']
].map(e => JSON.stringify(e))), 'Only selected incident relationships, including reciprocal/parallel/self calls, remain');
assert.strictEqual(incident.candidateEdges, 6);
assert.strictEqual(incident.truncatedEdges, false);
assert.strictEqual(graph.selectNeighborhood(triangle, 'A').edges.length, 10, 'Default one-hop mode retains neighbor-to-neighbor edges for broader exploration');
assert.strictEqual(graph.selectNeighborhood(triangle, 'A', { depth: 2 }).nodes.length, 4, 'Explicit two-hop exploration retains distant methods');
const moved = graph.selectNeighborhood(triangle, 'B', { incidentOnly: true });
assert(moved.edges.every(e => e.caller === 'B' || e.callee === 'B'), 'Selecting a neighbor recomputes its incident edge set');
assert(moved.edges.some(e => e.caller === 'B' && e.callee === 'B'), 'The newly selected method retains its self-call');
assert(!moved.edges.some(e => e.caller === 'A' && e.callee === 'A'), 'The previously selected method loses its unrelated self-call');
assert.strictEqual(triangle.nodes.length, 4, 'Selected views retain the full searchable node index');
assert.strictEqual(triangle.edges.length, 11, 'Selected views do not mutate the full relationship index');

// A high-degree graph stays bounded; searching can still use the entire index.
const starNodes = Array.from({ length: 1000 }, (_, i) => node('method' + i));
const star = graph.createIndex({ nodes: starNodes, edges: starNodes.slice(1).map(n => edge('method0', n.id)) });
const bounded = graph.selectNeighborhood(star, 'method0', { maxNodes: 5000 });
assert.strictEqual(star.nodes.length, 1000);
assert.strictEqual(bounded.nodes.length, 60);
assert.strictEqual(bounded.truncatedNodes, true);
assert(bounded.edges.length <= 180);
assert.deepStrictEqual(bounded.nodes.map(n => n.id), graph.selectNeighborhood(star, 'method0').nodes.map(n => n.id), 'Neighborhood selection is deterministic');
const incidentBounded = graph.selectNeighborhood(star, 'method0', { incidentOnly: true, maxNodes: 5000 });
assert.strictEqual(incidentBounded.nodes.length, 60, 'Selected-method mode bounds high-degree node rendering');
assert.strictEqual(incidentBounded.edges.length, 59);
assert.strictEqual(incidentBounded.truncatedNodes, true);
const parallelStar = graph.createIndex({ nodes: starNodes, edges: starNodes.slice(1).flatMap(n => [edge('method0', n.id), edge(n.id, 'method0'), edge('method0', n.id, 'declared'), edge(n.id, 'method0', 'declared')]) });
const incidentEdgesBounded = graph.selectNeighborhood(parallelStar, 'method0', { incidentOnly: true, maxEdges: 9999 });
assert.strictEqual(incidentEdgesBounded.nodes.length, 60);
assert.strictEqual(incidentEdgesBounded.edges.length, 180, 'Selected-method mode separately bounds parallel directed edges');
assert.strictEqual(incidentEdgesBounded.candidateEdges, 236);
assert.strictEqual(incidentEdgesBounded.truncatedEdges, true);
assert(incidentEdgesBounded.edges.every(e => e.caller === 'method0' || e.callee === 'method0'));

// Whole recorded graphs and the UI's selected-method mode never drop methods
// or relationships. The optional bounded helper remains available to callers.
const whole = graph.selectWholeGraph(parallelStar);
assert.strictEqual(whole.nodes.length, 1000);
assert.strictEqual(whole.edges.length, 3996);
assert.strictEqual(whole.truncatedNodes, false);
assert.strictEqual(whole.truncatedEdges, false);
assert.notStrictEqual(whole.nodes, parallelStar.nodes, 'Whole graph selectors do not expose mutable index arrays');
const uncapped = graph.selectNeighborhood(parallelStar, 'method0', { incidentOnly: true, uncapped: true });
assert.strictEqual(uncapped.nodes.length, 1000, 'High-degree selection includes every direct neighbor');
assert.strictEqual(uncapped.edges.length, 3996, 'High-degree selection includes every reciprocal and parallel relationship');
assert.strictEqual(uncapped.truncatedNodes, false);
assert.strictEqual(uncapped.truncatedEdges, false);
const fullTriangle = graph.selectWholeGraph(triangle);
assert.strictEqual(fullTriangle.edges.length, 11, 'Reset/full selection restores unrelated neighbor relationships');
assert.strictEqual(graph.selectNeighborhood(triangle, 'A', { incidentOnly: true, uncapped: true }).edges.length, 6, 'Uncapped mode still filters to the selected method');
assert.strictEqual(graph.selectWholeGraph(triangle).edges.length, 11, 'Switching scopes does not mutate the graph');

// A class scope uses exact declaring-class equality, retains every own method
// (including isolated ones), and adds only direct caller/callee endpoints.
const classNode = (id, className) => ({ ...node(id), className });
const classFixture = graph.createIndex({
  nodes: [classNode('a0', 'pkg.A'), classNode('a1', 'pkg.A'), classNode('aIsolated', 'pkg.A'),
    classNode('nested', 'pkg.A$Nested'), classNode('prefix', 'pkg.AB'), classNode('x', 'outside.X'),
    classNode('y', 'outside.Y'), classNode('z', 'outside.Z'), classNode('alone', 'pkg.Isolated')],
  edges: [edge('a0', 'a1'), edge('x', 'a0'), edge('x', 'a0', 'declared'), edge('a1', 'y', 'declared'),
    edge('a0', 'a0'), edge('a1', 'nested'), edge('y', 'x'), edge('y', 'y'), edge('x', 'z'), edge('nested', 'prefix')]
});
const fixtureEdgesBefore = JSON.stringify(classFixture.edges);
const classGraph = graph.selectClassGraph(classFixture, 'pkg.A');
assert.strictEqual(graph.selectInitialClass(classFixture, ['missing.Launcher', 'pkg.A']), 'pkg.A', 'Default comes from a manifest launcher with recorded nodes');
assert.strictEqual(graph.selectInitialClass(classFixture, ['outside.Y', 'pkg.A']), 'outside.Y', 'Launcher order is deterministic when several have observations');
assert.strictEqual(graph.selectInitialClass(classFixture, ['missing.Launcher']), null, 'Unrecorded launcher does not fall back to the whole graph');
assert.strictEqual(graph.selectInitialClass(classFixture, []), null, 'A manifest without a launcher requires explicit selection');
assert.strictEqual(graph.selectInitialClass(classFixture, null), null, 'Legacy reports never guess MainActivity or show the whole graph');
assert.strictEqual(classGraph.classMethodCount, 3, 'Nested and prefix-matching class names are not own methods');
assert.deepStrictEqual(new Set(classGraph.nodes.map(n => n.id)), new Set(['a0', 'a1', 'aIsolated', 'x', 'y', 'nested']));
assert.deepStrictEqual(new Set(classGraph.edges.map(e => JSON.stringify([e.caller, e.callee, e.kind]))), new Set([
  ['a0', 'a1', 'observed'], ['x', 'a0', 'observed'], ['x', 'a0', 'declared'],
  ['a1', 'y', 'declared'], ['a0', 'a0', 'observed'], ['a1', 'nested', 'observed']
].map(e => JSON.stringify(e))), 'Class graph excludes neighbor-to-neighbor edges, neighbor self-loops and transitive calls');
const scopedIndex = graph.createIndex(classGraph);
const externalFocus = graph.selectNeighborhood(scopedIndex, 'x', { incidentOnly: true, uncapped: true });
assert.deepStrictEqual(new Set(externalFocus.nodes.map(n => n.id)), new Set(['x', 'a0']));
assert.strictEqual(externalFocus.edges.length, 2, 'External focus keeps observed/declared calls inside the class scope');
assert.strictEqual(scopedIndex.incoming.get('x').length, 0, 'Inspector adjacency excludes unrelated incoming calls');
assert.strictEqual(scopedIndex.outgoing.get('x').length, 2, 'Inspector adjacency excludes external transitive calls');
assert.strictEqual(graph.selectNeighborhood(scopedIndex, 'z', { incidentOnly: true, uncapped: true }).nodes.length, 0, 'Out-of-scope methods cannot enter through focused navigation');
assert.deepStrictEqual(graph.selectClassGraph(classFixture, 'pkg.A').edges, classGraph.edges, 'Restoring the class graph after focus returns the same scope');
assert.strictEqual(JSON.stringify(classFixture.edges), fixtureEdgesBefore, 'Class filtering and scoped focus preserve the global graph');
const isolatedClass = graph.selectClassGraph(classFixture, 'pkg.Isolated');
assert.strictEqual(isolatedClass.nodes.length, 1);
assert.strictEqual(isolatedClass.edges.length, 0);
assert.strictEqual(isolatedClass.classMethodCount, 1);
assert.strictEqual(graph.selectClassGraph(classFixture, 'missing').nodes.length, 0);
assert.strictEqual(graph.selectClassGraph(classFixture, 'pkg').nodes.length, 0, 'A package prefix is not an exact class');
assert.strictEqual(graph.selectClassGraph(graph.createIndex({ nodes: [classNode('default', '')] }), '').nodes.length, 1, 'Default-package identifiers remain valid class scopes');
const denseClassMethods = Array.from({ length: 240 }, (_, i) => classNode('own' + i, 'dense.Owner'));
const denseClassEdges = denseClassMethods.flatMap(n => [edge(n.id, 'external'), edge(n.id, 'external', 'declared'), edge('external', n.id)]);
denseClassMethods.slice(1).forEach((n, i) => denseClassEdges.push(edge(denseClassMethods[i].id, n.id)));
denseClassEdges.push(edge('unrelated', 'external'));
const denseClassIndex = graph.createIndex({ nodes: [...denseClassMethods, classNode('external', 'external.Target'), classNode('unrelated', 'external.Other')], edges: denseClassEdges });
const denseClass = graph.selectClassGraph(denseClassIndex, 'dense.Owner');
assert.strictEqual(denseClass.classMethodCount, 240);
assert.strictEqual(denseClass.nodes.length, 241, 'A class graph is not capped at 60 nodes');
assert.strictEqual(denseClass.edges.length, 959, 'A class graph is not capped at 180 relationships');
assert.strictEqual(denseClass.truncatedNodes || denseClass.truncatedEdges, false);
const denseExternalFocus = graph.selectNeighborhood(graph.createIndex(denseClass), 'external', { incidentOnly: true, uncapped: true });
assert.strictEqual(denseExternalFocus.edges.length, 720, 'High-degree external focus is uncapped but still class-scoped');
assert(!denseExternalFocus.nodes.some(n => n.id === 'unrelated'));

// Dense local graphs separately bound SVG edges, even when every method fits.
const denseNodes = Array.from({ length: 30 }, (_, i) => node('n' + i));
const denseEdges = [];
denseNodes.forEach(a => denseNodes.forEach(b => { if (a.id !== b.id) denseEdges.push(edge(a.id, b.id)); }));
const dense = graph.selectNeighborhood(graph.createIndex({ nodes: denseNodes, edges: denseEdges }), 'n0');
assert.strictEqual(dense.nodes.length, 30);
assert.strictEqual(dense.edges.length, 180);
assert.strictEqual(dense.truncatedEdges, true);
assert.strictEqual(dense.candidateEdges, 870);
assert.strictEqual(graph.selectWholeGraph(graph.createIndex({ nodes: denseNodes, edges: denseEdges })).edges.length, 870, 'Whole graph preserves every edge in dense graphs');

// Whole-graph layout condenses cycles and assigns deterministic finite layers.
// A long chain verifies the traversal is iterative rather than recursive.
const originalTriangle = JSON.stringify(triangle.edges);
const fullLayout = graph.layoutWholeGraph(triangle);
assert.strictEqual(fullLayout.positions.size, 4);
assert.strictEqual(fullLayout.componentCount, 2, 'The A/B/C cycle is one strongly connected component');
assert(fullLayout.positions.get('D').x > fullLayout.positions.get('C').x, 'DAG callees follow their caller component');
assert.deepStrictEqual([...fullLayout.positions], [...graph.layoutWholeGraph(triangle).positions], 'Whole-graph layout is deterministic');
assert.strictEqual(JSON.stringify(triangle.edges), originalTriangle, 'Layout does not mutate source relationships');
const manyLayout = graph.layoutWholeGraph(parallelStar);
assert.strictEqual(manyLayout.positions.size, 1000);
assert.strictEqual(new Set([...manyLayout.positions.values()].map(p => p.x + ',' + p.y)).size, 1000, 'Every whole-graph method gets a distinct position');
manyLayout.positions.forEach(p => {
  assert(p.x - p.width / 2 >= manyLayout.bounds.x && p.x + p.width / 2 <= manyLayout.bounds.x + manyLayout.bounds.width);
  assert(p.y - p.height / 2 >= manyLayout.bounds.y && p.y + p.height / 2 <= manyLayout.bounds.y + manyLayout.bounds.height);
});
const uncappedLayout = graph.layoutNeighborhood(uncapped);
assert.strictEqual(uncappedLayout.positions.size, 1000);
assert.strictEqual(new Set([...uncappedLayout.positions.values()].map(p => p.x + ',' + p.y)).size, 1000, 'High-degree incident layout retains separate positions');
const longNodes = Array.from({ length: 12000 }, (_, i) => node('chain' + i));
const longIndex = graph.createIndex({ nodes: longNodes, edges: longNodes.slice(1).map((n, i) => edge(longNodes[i].id, n.id)) });
const longLayout = graph.layoutWholeGraph(longIndex);
assert.strictEqual(longLayout.positions.size, 12000, 'Long call chains do not overflow the call stack');
assert.strictEqual(longLayout.componentCount, 12000);
assert(Number.isFinite(longLayout.bounds.width) && longLayout.bounds.width > 8000, 'Whole layout supports bounds beyond the former zoom cap');
assert.strictEqual(graph.layoutWholeGraph(graph.createIndex({})).positions.size, 0);

// Isolated methods, long signatures, and arbitrary identifiers retain valid
// geometry. Self-loops and reciprocal relationships have directed paths.
const isolated = graph.selectNeighborhood(index, '<evil>');
const isolatedLayout = graph.layoutNeighborhood(isolated);
assert.strictEqual(isolatedLayout.positions.size, 1);
const isolatedPosition = isolatedLayout.positions.get('<evil>');
assert(isolatedPosition.x >= isolatedLayout.bounds.x && isolatedPosition.x <= isolatedLayout.bounds.x + isolatedLayout.bounds.width);
const layout = graph.layoutNeighborhood(two);
layout.positions.forEach(p => ['x', 'y', 'width', 'height'].forEach(key => assert(Number.isFinite(p[key]))));
const a = layout.positions.get('A'), b = layout.positions.get('B');
const loop = graph.edgePath(a, a, 0);
assert(loop.includes(' C '), 'Self-calls use a visible loop');
assert.notStrictEqual(graph.edgePath(a, a, 30), graph.edgePath(a, a, -30), 'Observed and declared self-calls can be separately selected');
const loopLayout = graph.layoutNeighborhood(graph.selectNeighborhood(graph.createIndex({ nodes: [node('root'), node('leaf')], edges: [edge('root', 'leaf'), edge('leaf', 'leaf')] }), 'root'));
const leaf = loopLayout.positions.get('leaf');
assert(loopLayout.bounds.x + loopLayout.bounds.width >= leaf.x + 245, 'Fit bounds include self-loop control points');
const loopNumbers = graph.edgePath(leaf, leaf, 30).match(/-?\d+(?:\.\d+)?/g).map(Number);
for (let t = 0; t <= 1; t += .05) {
  const y = Math.pow(1-t,3)*loopNumbers[1] + 3*Math.pow(1-t,2)*t*loopNumbers[3] + 3*(1-t)*t*t*loopNumbers[5] + t*t*t*loopNumbers[7];
  assert(Math.abs(y-leaf.y) < 44, 'Self-loop stays inside its method row instead of crossing the adjacent node');
}
assert.notStrictEqual(graph.edgePath(a, b, 30), graph.edgePath(b, a, 30));
[loop, graph.edgePath(a, b, 0), graph.edgePath(a, b, 30)].forEach(path => assert(!/NaN|Infinity/.test(path)));
assert.strictEqual(graph.createIndex({}).nodes.length, 0);
assert.strictEqual(graph.createIndex({ nodes: [node('A'), node('A'), null], edges: [null, edge('A', 'missing')] }).edges.length, 0);

console.log('report-graph: all assertions passed');
