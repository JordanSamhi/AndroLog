(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.AndroLogGraph = api;
}(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg';
  var nextMount = 0;
  function array(value) { return Array.isArray(value) ? value : []; }
  function count(value) { var n = Number(value); return Number.isFinite(n) && n >= 0 ? n : 0; }
  function compare(a, b) { return String(a).localeCompare(String(b)); }
  function edgeOrder(a, b) {
    return (a.kind === 'observed' ? 0 : 1) - (b.kind === 'observed' ? 0 : 1)
      || b.count - a.count || compare(a.caller, b.caller) || compare(a.callee, b.callee);
  }
  function createIndex(graph) {
    graph = graph || {};
    var index = { nodes: [], edges: [], byId: new Map(), outgoing: new Map(), incoming: new Map(), incident: new Map() };
    array(graph.nodes).forEach(function (node) {
      if (!node || typeof node.id !== 'string' || index.byId.has(node.id)) return;
      var copy = { id: node.id, className: String(node.className || ''), label: String(node.label || node.id), inReport: node.inReport === true };
      copy.searchText = (copy.id + ' ' + copy.className + ' ' + copy.label).toLowerCase();
      index.byId.set(copy.id, copy); index.nodes.push(copy);
      index.outgoing.set(copy.id, []); index.incoming.set(copy.id, []); index.incident.set(copy.id, []);
    });
    index.nodes.sort(function (a, b) { return compare(a.className, b.className) || compare(a.label, b.label) || compare(a.id, b.id); });
    var grouped = new Map();
    array(graph.edges).forEach(function (edge) {
      if (!edge || !index.byId.has(edge.caller) || !index.byId.has(edge.callee)) return;
      var kind = edge.kind === 'observed' ? 'observed' : 'declared';
      var key = JSON.stringify([edge.caller, edge.callee, kind]);
      var copy = grouped.get(key);
      if (!copy) {
        copy = { id: key, caller: edge.caller, callee: edge.callee, kind: kind, count: 0, callSites: [] };
        grouped.set(key, copy);
      }
      copy.count += count(edge.count);
      array(edge.callSites).forEach(function (site) {
        if (site && typeof site === 'object') copy.callSites.push({ id: String(site.id || ''), declared: String(site.declared || ''), kind: String(site.kind || '') });
      });
    });
    grouped.forEach(function (edge) {
      index.edges.push(edge);
      index.outgoing.get(edge.caller).push(edge); index.incoming.get(edge.callee).push(edge);
      index.incident.get(edge.caller).push(edge);
      if (edge.caller !== edge.callee) index.incident.get(edge.callee).push(edge);
    });
    index.edges.sort(edgeOrder);
    index.incident.forEach(function (edges) { edges.sort(edgeOrder); });
    return index;
  }
  function selectNeighborhood(index, focusId, options) {
    options = options || {};
    var maxNodes = options.uncapped ? Math.max(1, index.nodes.length) : Math.max(1, Math.min(60, Math.floor(count(options.maxNodes) || 60)));
    var maxEdges = options.uncapped ? Math.max(1, index.edges.length) : Math.max(1, Math.min(180, Math.floor(count(options.maxEdges) || 180)));
    var incidentOnly = options.incidentOnly === true;
    var depth = !incidentOnly && options.depth === 2 ? 2 : 1;
    var result = { focusId: focusId, nodes: [], edges: [], levels: new Map(), truncatedNodes: false, truncatedEdges: false, candidateEdges: 0 };
    if (!index.byId.has(focusId)) return result;
    var selected = new Set([focusId]), queue = [{ id: focusId, distance: 0, side: 0 }];
    result.levels.set(focusId, 0);
    for (var cursor = 0; cursor < queue.length; cursor++) {
      var item = queue[cursor];
      if (item.distance >= depth) continue;
      index.incident.get(item.id).forEach(function (edge) {
        var id = edge.caller === item.id ? edge.callee : edge.caller;
        if (selected.has(id)) return;
        if (selected.size >= maxNodes) { result.truncatedNodes = true; return; }
        var side = item.side || (edge.caller === item.id ? 1 : -1);
        selected.add(id); result.levels.set(id, side * (item.distance + 1));
        queue.push({ id: id, distance: item.distance + 1, side: side });
      });
    }
    selected.forEach(function (id) { result.nodes.push(index.byId.get(id)); });
    var edges = [];
    selected.forEach(function (id) {
      index.outgoing.get(id).forEach(function (edge) {
        if (selected.has(edge.callee) && (!incidentOnly || edge.caller === focusId || edge.callee === focusId)) edges.push(edge);
      });
    });
    edges.sort(edgeOrder); result.candidateEdges = edges.length;
    result.edges = edges.slice(0, maxEdges); result.truncatedEdges = edges.length > maxEdges;
    return result;
  }
  function selectWholeGraph(index) {
    return { focusId: null, nodes: index.nodes.slice(), edges: index.edges.slice(), levels: new Map(), truncatedNodes: false, truncatedEdges: false, candidateEdges: index.edges.length };
  }
  function selectClassGraph(index, className) {
    var own = new Set(), included = new Set();
    index.nodes.forEach(function (node) {
      if (node.className === className) { own.add(node.id); included.add(node.id); }
    });
    var edges = index.edges.filter(function (edge) {
      if (!own.has(edge.caller) && !own.has(edge.callee)) return false;
      included.add(edge.caller); included.add(edge.callee); return true;
    });
    return { focusId: null, className: className, classMethodCount: own.size,
      nodes: index.nodes.filter(function (node) { return included.has(node.id); }), edges: edges,
      levels: new Map(), truncatedNodes: false, truncatedEdges: false, candidateEdges: edges.length };
  }
  function selectInitialClass(index, launcherActivities) {
    var available = new Set(index.nodes.map(function (node) { return node.className; }));
    return array(launcherActivities).find(function (name) { return typeof name === 'string' && available.has(name); }) || null;
  }
  function boundsFor(positions, edges) {
    var minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    positions.forEach(function (p) {
      minX = Math.min(minX, p.x - 170); maxX = Math.max(maxX, p.x + 170);
      minY = Math.min(minY, p.y - 100); maxY = Math.max(maxY, p.y + 100);
    });
    edges.forEach(function (edge) {
      if (edge.caller === edge.callee && positions.has(edge.caller)) maxX = Math.max(maxX, positions.get(edge.caller).x + 260);
    });
    if (!positions.size) return { x: -325, y: -215, width: 650, height: 430 };
    var width = Math.max(650, maxX - minX), height = Math.max(430, maxY - minY);
    return { x: (minX + maxX - width) / 2, y: (minY + maxY - height) / 2, width: width, height: height };
  }
  // Iterative Kosaraju traversal avoids recursion limits on long call chains.
  // Condensing cycles before assigning DAG layers keeps layout deterministic
  // and linear apart from sorting, without a force simulation or edge pruning.
  function layoutWholeGraph(index) {
    var visited = new Set(), finished = [];
    index.nodes.forEach(function (node) {
      if (visited.has(node.id)) return;
      var stack = [{ id: node.id, next: 0 }]; visited.add(node.id);
      while (stack.length) {
        var frame = stack[stack.length - 1], outgoing = index.outgoing.get(frame.id);
        if (frame.next < outgoing.length) {
          var target = outgoing[frame.next++].callee;
          if (!visited.has(target)) { visited.add(target); stack.push({ id: target, next: 0 }); }
        } else { finished.push(frame.id); stack.pop(); }
      }
    });
    var components = [], componentFor = new Map();
    for (var f = finished.length - 1; f >= 0; f--) {
      var seed = finished[f]; if (componentFor.has(seed)) continue;
      var component = { ids: [], outgoing: new Set(), indegree: 0, rank: 0 }, componentId = components.length;
      components.push(component); var pending = [seed]; componentFor.set(seed, componentId);
      while (pending.length) {
        var id = pending.pop(); component.ids.push(id);
        index.incoming.get(id).forEach(function (edge) {
          if (!componentFor.has(edge.caller)) { componentFor.set(edge.caller, componentId); pending.push(edge.caller); }
        });
      }
      component.ids.sort(compare);
    }
    index.edges.forEach(function (edge) {
      var from = componentFor.get(edge.caller), to = componentFor.get(edge.callee);
      if (from !== to && !components[from].outgoing.has(to)) { components[from].outgoing.add(to); components[to].indegree++; }
    });
    var ready = [];
    components.forEach(function (component, i) { if (!component.indegree) ready.push(i); });
    for (var q = 0; q < ready.length; q++) {
      var current = components[ready[q]];
      current.outgoing.forEach(function (target) {
        var next = components[target]; next.rank = Math.max(next.rank, current.rank + 1); next.indegree--;
        if (!next.indegree) ready.push(target);
      });
    }
    var layers = new Map();
    components.forEach(function (component) {
      if (!layers.has(component.rank)) layers.set(component.rank, []);
      layers.get(component.rank).push(component);
    });
    var positions = new Map(), x = 0;
    var rowsPerColumn = Math.max(12, Math.ceil(Math.sqrt(Math.max(1, index.nodes.length) * 330 / 88 / 1.7)));
    Array.from(layers.keys()).sort(function (a, b) { return a - b; }).forEach(function (rank) {
      var ids = [];
      layers.get(rank).sort(function (a, b) { return compare(a.ids[0], b.ids[0]); }).forEach(function (component) { component.ids.forEach(function (id) { ids.push(id); }); });
      ids.forEach(function (id, i) {
        var column = Math.floor(i / rowsPerColumn), rows = Math.min(rowsPerColumn, ids.length - column * rowsPerColumn);
        positions.set(id, { x: x + column * 330, y: (i % rowsPerColumn - (rows - 1) / 2) * 88, width: 228, height: 54 });
      });
      x += Math.max(1, Math.ceil(ids.length / rowsPerColumn)) * 330 + 90;
    });
    return { positions: positions, bounds: boundsFor(positions, index.edges), componentCount: components.length };
  }
  function layoutNeighborhood(neighborhood) {
    var positions = new Map(), layers = new Map();
    neighborhood.nodes.forEach(function (node) {
      var level = neighborhood.levels.get(node.id) || 0;
      if (!layers.has(level)) layers.set(level, []);
      layers.get(level).push(node);
    });
    layers.forEach(function (nodes, level) {
      nodes.sort(function (a, b) { return compare(a.label, b.label) || compare(a.id, b.id); });
      var rowsPerColumn = neighborhood.nodes.length > 60 ? Math.max(12, Math.ceil(Math.sqrt(neighborhood.nodes.length * 330 / 88))) : nodes.length;
      nodes.forEach(function (node, i) {
        var column = Math.floor(i / rowsPerColumn), rows = Math.min(rowsPerColumn, nodes.length - column * rowsPerColumn);
        positions.set(node.id, { x: level * 330 + (level < 0 ? -1 : 1) * column * 330, y: (i % rowsPerColumn - (rows - 1) / 2) * 88, width: 228, height: 54 });
      });
    });
    return { positions: positions, bounds: boundsFor(positions, neighborhood.edges) };
  }
  function edgePath(from, to, curved) {
    if (from === to) {
      // Keep the loop beside its method so it cannot pass behind the previous
      // method in the same column. Parallel kinds have separately hitable arcs.
      var loopWidth = curved < 0 ? 185 : curved > 0 ? 245 : 215;
      return 'M ' + (from.x + from.width / 2 + 3) + ' ' + (from.y - 12) + ' C ' + (from.x + loopWidth) + ' ' + (from.y - 60) + ', ' + (from.x + loopWidth) + ' ' + (from.y + 60) + ', ' + (from.x + from.width / 2 + 3) + ' ' + (from.y + 12);
    }
    var dx = to.x - from.x, dy = to.y - from.y;
    var scale = Math.min((from.width / 2 + 3) / Math.max(Math.abs(dx), .001), (from.height / 2 + 3) / Math.max(Math.abs(dy), .001));
    var endScale = Math.min((to.width / 2 + 5) / Math.max(Math.abs(dx), .001), (to.height / 2 + 5) / Math.max(Math.abs(dy), .001));
    var startX = from.x + dx * scale, startY = from.y + dy * scale;
    var endX = to.x - dx * endScale, endY = to.y - dy * endScale;
    if (!curved) return 'M ' + startX + ' ' + startY + ' L ' + endX + ' ' + endY;
    var length = Math.sqrt(dx * dx + dy * dy) || 1;
    return 'M ' + startX + ' ' + startY + ' Q ' + ((startX + endX) / 2 - dy / length * curved) + ' ' + ((startY + endY) / 2 + dx / length * curved) + ' ' + endX + ' ' + endY;
  }
  function mount(container, graph, options) {
    options = options || {}; graph = graph || {};
    var doc = container.ownerDocument, mountId = 'androlog-graph-' + (++nextMount), disposers = [];
    function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
    function el(tag, className, text) {
      var node = doc.createElement(tag); if (className) node.className = className;
      if (text !== undefined && text !== null) node.textContent = String(text); return node;
    }
    function svgEl(tag, attributes, text) {
      var node = doc.createElementNS(NS, tag);
      Object.keys(attributes || {}).forEach(function (key) { node.setAttribute(key, String(attributes[key])); });
      if (text !== undefined) node.textContent = String(text); return node;
    }
    function listen(node, event, handler, config) { node.addEventListener(event, handler, config); disposers.push(function () { node.removeEventListener(event, handler, config); }); }
    function button(text, className, action) { var b = el('button', className || 'button', text); b.type = 'button'; b.addEventListener('click', action); return b; }
    function fmt(n) { return count(n).toLocaleString('en-US'); }
    function shorten(text, length) { return text.length > length ? text.slice(0, length - 1) + '…' : text; }
    clear(container);
    if (!graph.available || !array(graph.nodes).length) {
      var empty = el('div', 'graph-empty');
      empty.appendChild(el('div', 'graph-empty-symbol', '↗'));
      empty.appendChild(el('h2', '', graph.available ? 'No dynamic calls were recorded' : 'No dynamic call graph in this report'));
      empty.appendChild(el('p', '', graph.available ? 'The supplied log contains no method relationships to display.' : 'Run the application with method-call instrumentation (-mc), then generate a report from that execution log.'));
      if (graph.note) empty.appendChild(el('p', 'graph-note', graph.note));
      container.appendChild(empty);
      return { destroy: function () { clear(container); } };
    }
    var index = createIndex(graph);
    if (!index.nodes.length) {
      container.appendChild(el('p', 'graph-empty', 'No valid method nodes are available in this dynamic call graph.'));
      return { destroy: function () { clear(container); } };
    }
    var launchers = new Set(array(graph.launcherActivities));
    var focusId = null, selectedNode = null, selectedClass = selectInitialClass(index, graph.launcherActivities), selectedEdge = null, searchLimit = 100;
    var neighborhood, layout, view, selectedElements = new Map(), edgeElements = new Map(), currentScene = null, classContext = null;
    var emptyIndex = createIndex({}), emptyNeighborhood = selectWholeGraph(emptyIndex), emptyLayout = layoutWholeGraph(emptyIndex), emptyScene = null;
    var classes = [], classByName = new Map(), expandedClasses = new Set(), methodLimits = new Map(), edgeById = new Map();
    index.nodes.forEach(function (node) {
      var item = classByName.get(node.className);
      if (!item) { item = { name: node.className, methods: [] }; classByName.set(node.className, item); classes.push(item); }
      item.methods.push(node);
    });
    classes.sort(function (a, b) { return Number(launchers.has(b.name)) - Number(launchers.has(a.name)) || compare(a.name, b.name); });
    index.edges.forEach(function (edge) { edgeById.set(edge.id, edge); });
    var header = el('div', 'graph-header');
    var titleBlock = el('div'); titleBlock.appendChild(el('h2', '', 'Dynamic call graph'));
    titleBlock.appendChild(el('p', 'graph-note', graph.note || 'Relationships found in the supplied execution trace. This view is not a complete static call graph.')); header.appendChild(titleBlock);
    var totals = el('p', 'graph-totals');
    var observed = index.edges.filter(function (edge) { return edge.kind === 'observed'; }).length;
    totals.textContent = 'Recorded execution: ' + fmt(observed) + ' confirmed relationships · ' + fmt(index.edges.length - observed) + ' declared relationships · ' + fmt(graph.stats && graph.stats.unresolvedSites) + ' unconfirmed callsites';
    header.appendChild(totals); container.appendChild(header);
    var shell = el('div', 'graph-shell'), sidebar = el('aside', 'graph-sidebar'); sidebar.setAttribute('aria-label', 'Classes and methods in the dynamic call graph');
    var searchLabel = el('label', 'search-label', 'Find a class or method'); searchLabel.htmlFor = mountId + '-search'; sidebar.appendChild(searchLabel);
    var search = el('input'); search.type = 'search'; search.id = searchLabel.htmlFor; search.placeholder = 'Search all classes and methods…'; search.autocomplete = 'off'; search.spellcheck = false; sidebar.appendChild(search);
    var searchCount = el('p', 'graph-search-count'); searchCount.setAttribute('aria-live', 'polite'); sidebar.appendChild(searchCount);
    var results = el('div', 'graph-search-results'); sidebar.appendChild(results);
    var more = button('Show more classes', 'button graph-more', function () {
      var next = searchLimit; searchLimit += 100; renderSearch();
      if (results.children[next]) results.children[next].querySelector('.graph-class-choice').focus();
    }); sidebar.appendChild(more);
    shell.appendChild(sidebar);
    var content = el('div', 'graph-content'), toolbar = el('div', 'graph-toolbar');
    var scopeLabel = el('span', 'graph-scope-label'); toolbar.appendChild(scopeLabel);
    var tools = el('div', 'graph-tools');
    tools.appendChild(button('−', 'button', function () { zoom(1 / 1.25); })); tools.lastChild.setAttribute('aria-label', 'Zoom out');
    tools.appendChild(button('+', 'button', function () { zoom(1.25); })); tools.lastChild.setAttribute('aria-label', 'Zoom in');
    tools.appendChild(button('Fit', 'button', fit)); toolbar.appendChild(tools);
    var reset = button('Reset graph', 'button graph-reset', resetGraph); tools.appendChild(reset);
    content.appendChild(toolbar);
    var canvas = el('div', 'graph-canvas');
    var startMessage = el('div', 'graph-start-empty'); startMessage.setAttribute('role', 'status'); canvas.appendChild(startMessage);
    var svg = svgEl('svg', { class: 'graph-svg', role: 'group', tabindex: '0' });
    var defs = svgEl('defs');
    ['observed', 'declared'].forEach(function (kind) {
      var marker = svgEl('marker', { id: mountId + '-' + kind, markerWidth: 8, markerHeight: 8, refX: 7, refY: 4, orient: 'auto', markerUnits: 'strokeWidth' });
      marker.appendChild(svgEl('path', { d: 'M 0 0 L 8 4 L 0 8 z', fill: kind === 'observed' ? '#258677' : '#7d899c' })); defs.appendChild(marker);
    }); svg.appendChild(defs);
    canvas.appendChild(svg);
    var gestureHelp = el('p', 'graph-gesture-help'); canvas.appendChild(gestureHelp); content.appendChild(canvas);
    var status = el('div', 'graph-status'); status.setAttribute('aria-live', 'polite'); content.appendChild(status);
    var legend = el('div', 'graph-legend');
    legend.appendChild(el('span', 'graph-solid-key', 'Confirmed runtime target'));
    legend.appendChild(el('span', 'graph-dashed-key', 'Declared target; actual target unconfirmed')); content.appendChild(legend);
    var inspector = el('section', 'graph-inspector'); inspector.setAttribute('aria-label', 'Selected method or call relationship'); content.appendChild(inspector);
    shell.appendChild(content); container.appendChild(shell);

    function renderSearch() {
      var query = search.value.trim().toLowerCase();
      var matches = [], matchingMethods = 0;
      classes.forEach(function (item) {
        var methods = item.methods.filter(function (node) { return node.searchText.indexOf(query) !== -1; });
        if (methods.length) { matches.push({ item: item, methods: methods }); matchingMethods += methods.length; }
      });
      clear(results);
      matches.slice(0, searchLimit).forEach(function (match) {
        var item = match.item, group = el('div', 'graph-class-group'), head = el('div', 'graph-class-head');
        var children = el('div', 'graph-class-methods'), expanded = expandedClasses.has(item.name);
        var toggle = button(expanded ? '▾' : '▸', 'graph-class-toggle', function () {
          if (expandedClasses.has(item.name)) expandedClasses.delete(item.name); else expandedClasses.add(item.name);
          renderSearch();
          var replacement = Array.from(results.querySelectorAll('.graph-class-toggle')).find(function (b) { return b.getAttribute('data-graph-class') === item.name; });
          if (replacement) replacement.focus({ preventScroll: true });
        });
        toggle.setAttribute('aria-label', 'Show methods of ' + (item.name || '(default package)'));
        toggle.setAttribute('aria-expanded', String(expanded)); toggle.setAttribute('data-graph-class', item.name); head.appendChild(toggle);
        var choice = button('', 'graph-class-choice', function () { selectClass(item.name); }); choice.title = item.name;
        choice.setAttribute('data-graph-class', item.name); choice.setAttribute('aria-pressed', String(selectedClass === item.name));
        choice.appendChild(el('strong', '', item.name || '(default package)'));
        choice.appendChild(el('span', '', fmt(match.methods.length) + (query ? ' matching methods' : ' methods') + (launchers.has(item.name) ? ' · Launcher activity' : ''))); head.appendChild(choice); group.appendChild(head);
        if (expanded) {
          var limit = methodLimits.get(item.name) || 100;
          match.methods.slice(0, limit).forEach(function (node) {
            var b = button('', 'graph-search-method', function () { locateNode(node.id); }); b.title = node.id;
            b.setAttribute('data-graph-method', node.id); b.setAttribute('aria-pressed', String(node.id === selectedNode));
            b.appendChild(el('strong', '', node.label)); b.appendChild(el('span', '', 'Locate in its class graph')); children.appendChild(b);
          });
          if (limit < match.methods.length) children.appendChild(button('Show more methods · ' + fmt(match.methods.length - limit) + ' remaining', 'button graph-class-more', function () {
            methodLimits.set(item.name, limit + 100); renderSearch();
            var next = match.methods[limit];
            var target = Array.from(results.querySelectorAll('.graph-search-method')).find(function (b) { return b.getAttribute('data-graph-method') === next.id; });
            if (target) target.focus({ preventScroll: true });
          }));
          group.appendChild(children);
        }
        results.appendChild(group);
      });
      if (!matches.length) results.appendChild(el('p', 'graph-no-results', 'No matching classes or methods.'));
      searchCount.textContent = fmt(Math.min(matches.length, searchLimit)) + ' of ' + fmt(matches.length) + ' classes · ' + fmt(matchingMethods) + ' methods'; more.hidden = matches.length <= searchLimit;
    }
    function getClassContext(name) {
      if (!classContext || classContext.name !== name) {
        var subgraph = selectClassGraph(index, name), scopedIndex = createIndex(subgraph);
        classContext = { name: name, graph: subgraph, index: scopedIndex, layout: layoutWholeGraph(scopedIndex), scene: null };
      }
      return classContext;
    }
    function activeIndex() { return selectedClass === null ? emptyIndex : getClassContext(selectedClass).index; }
    function focus(id) {
      if (!activeIndex().byId.has(id)) return;
      focusId = id; selectedNode = id; selectedEdge = null; renderGraph(false); renderSearch();
      var target = selectedElements.get(id); if (target) target.focus({ preventScroll: true });
    }
    function selectClass(name) {
      focusId = null; selectedNode = null; selectedEdge = null; selectedClass = name;
      renderGraph(); renderSearch();
      var target = Array.from(results.querySelectorAll('.graph-class-choice')).find(function (b) { return b.getAttribute('data-graph-class') === name; });
      if (target) target.focus({ preventScroll: true });
    }
    function resetGraph() {
      focusId = null; selectedNode = null; selectedEdge = null;
      renderGraph(false); renderSearch();
    }
    function locateNode(id) {
      if (!index.byId.has(id)) return;
      focusId = null; selectedNode = id; selectedEdge = null; selectedClass = index.byId.get(id).className;
      renderGraph(); renderSearch(); zoomToNodes([id]);
      var target = selectedElements.get(id); if (target) target.focus({ preventScroll: true });
    }
    function selectNode(id) { focus(id); }
    function selectEdge(edge) {
      if (!edge || !neighborhood.edges.some(function (candidate) { return candidate.id === edge.id; })) return;
      selectedNode = null; selectedEdge = edge; highlight(); renderInspector();
    }
    function highlight() {
      selectedElements.forEach(function (element, id) {
        var node = index.byId.get(id);
        var className = 'graph-node' + (id === focusId ? ' is-focus' : '') + (id === selectedNode ? ' is-selected' : '') + (selectedClass !== null && node.className === selectedClass ? ' is-class-selected' : '') + (node.inReport ? '' : ' is-external');
        if (element.getAttribute('class') !== className) element.setAttribute('class', className);
        var pressed = id === selectedNode ? 'true' : 'false'; if (element.getAttribute('aria-pressed') !== pressed) element.setAttribute('aria-pressed', pressed);
      });
      edgeElements.forEach(function (element, id) {
        var edge = edgeById.get(id), incident = edge.caller === focusId || edge.callee === focusId;
        element.classList.toggle('is-selected', !!selectedEdge && selectedEdge.id === id);
        element.classList.toggle('is-muted', focusId !== null && !incident);
        element.classList.toggle('is-incident', focusId !== null && incident);
      });
    }
    function nodeButton(id, label) {
      var b = button(label || index.byId.get(id).label, 'graph-neighbor', function () { focus(id); }); b.title = id; return b;
    }
    function renderInspector() {
      clear(inspector);
      var scopedIndex = activeIndex();
      if (selectedClass !== null) inspector.appendChild(el('p', 'graph-note', 'Class scope: ' + (selectedClass || '(default package)')));
      if (selectedEdge) {
        var edge = selectedEdge;
        inspector.appendChild(el('p', 'graph-inspector-kind', edge.kind === 'observed' ? 'Confirmed runtime target' : 'Declared target · actual target unconfirmed'));
        var relation = el('div', 'graph-relation'); relation.appendChild(nodeButton(edge.caller, edge.caller)); relation.appendChild(el('span', '', '→')); relation.appendChild(nodeButton(edge.callee, edge.callee)); inspector.appendChild(relation);
        inspector.appendChild(el('p', 'graph-record-count', fmt(edge.count) + ' distinct records. Counts are not invocation frequencies.'));
        var details = el('details', 'graph-callsites'); details.appendChild(el('summary', '', 'Callsites (' + fmt(edge.callSites.length) + ')'));
        var sites = el('div', 'graph-callsite-list'), siteLimit = 25;
        function renderSites() {
          clear(sites);
          edge.callSites.slice(0, siteLimit).forEach(function (site) {
            var item = el('div', 'graph-callsite'); item.appendChild(el('code', '', site.id || '(site ID unavailable)'));
            item.appendChild(el('p', '', site.declared || '(declared target unavailable)')); if (site.kind) item.appendChild(el('span', '', site.kind)); sites.appendChild(item);
          });
          if (!edge.callSites.length) sites.appendChild(el('p', '', 'No callsite metadata was included for this relationship.'));
          if (siteLimit < edge.callSites.length) sites.appendChild(button('Show more callsites · ' + fmt(edge.callSites.length - siteLimit) + ' remaining', 'button', function () { siteLimit += 25; renderSites(); }));
        }
        details.addEventListener('toggle', function () { if (details.open && !sites.firstChild) renderSites(); });
        details.appendChild(sites); inspector.appendChild(details); return;
      }
      if (!selectedNode && !focusId) {
        if (selectedClass !== null && classByName.has(selectedClass)) {
          var item = classByName.get(selectedClass);
          inspector.appendChild(el('p', 'graph-inspector-kind', launchers.has(selectedClass) ? 'Manifest launcher activity · dynamic call graph' : 'Selected class dynamic call graph'));
          inspector.appendChild(el('h3', 'graph-method-title', item.name || '(default package)'));
          inspector.appendChild(el('p', 'graph-record-count', fmt(item.methods.length) + ' class methods · ' + fmt(scopedIndex.nodes.length - item.methods.length) + ' direct caller/callee methods · ' + fmt(scopedIndex.edges.length) + ' relationships. Every relationship has an endpoint in this class.'));
          inspector.appendChild(button('Locate class', 'button graph-locate-class', function () { zoomToNodes(item.methods.map(function (node) { return node.id; })); }));
        } else {
          inspector.appendChild(el('p', 'graph-inspector-kind', 'Choose a starting class'));
          inspector.appendChild(el('p', 'graph-record-count', 'Select a class in the sidebar to explore its recorded incoming and outgoing calls.'));
        }
        return;
      }
      var node = scopedIndex.byId.get(selectedNode || focusId);
      inspector.appendChild(el('p', 'graph-inspector-kind', node.inReport ? 'Method in this report' : 'External or unavailable code'));
      inspector.appendChild(el('h3', 'graph-method-title', node.id));
      var actions = el('div', 'graph-inspector-actions');
      var focusButton = button(node.id === focusId ? 'Calls highlighted' : 'Highlight incoming/outgoing', 'button', function () { focus(node.id); }); focusButton.disabled = node.id === focusId; actions.appendChild(focusButton);
      var open = button('Open code', 'button graph-open-code', function () { if (node.inReport && typeof options.navigate === 'function') options.navigate(node.className, node.id); }); open.disabled = !node.inReport; actions.appendChild(open);
      actions.appendChild(el('span', '', fmt(scopedIndex.incoming.get(node.id).length) + ' incoming · ' + fmt(scopedIndex.outgoing.get(node.id).length) + ' outgoing relationships')); inspector.appendChild(actions);
      var neighbors = new Map();
      scopedIndex.incident.get(node.id).forEach(function (edge) {
        var id = edge.caller === node.id ? edge.callee : edge.caller;
        if (id !== node.id && !neighbors.has(id)) neighbors.set(id, edge.caller === node.id ? 'Calls ' : 'Called by ');
      });
      if (neighbors.size) {
        var neighborList = el('div', 'graph-neighbors');
        Array.from(neighbors.keys()).slice(0, 24).forEach(function (id) {
          var b = nodeButton(id, neighbors.get(id) + index.byId.get(id).label); b.title = index.byId.get(id).id; neighborList.appendChild(b);
        });
        if (neighbors.size > 24) neighborList.appendChild(el('span', 'small', '24 of ' + fmt(neighbors.size) + ' neighbors listed; all their relationships remain in the graph.'));
        inspector.appendChild(neighborList);
      }
    }
    function createScene(subgraph, sceneLayout) {
      var scene = { root: svgEl('g', { class: 'graph-scene' }), nodes: new Map(), edges: new Map() };
      var edgeLayer = svgEl('g'), nodeLayer = svgEl('g'); scene.root.appendChild(edgeLayer); scene.root.appendChild(nodeLayer);
      var pairs = new Map(); subgraph.edges.forEach(function (edge) { var key = JSON.stringify([edge.caller, edge.callee]); pairs.set(key, (pairs.get(key) || 0) + 1); });
      subgraph.edges.forEach(function (edge) {
        var from = sceneLayout.positions.get(edge.caller), to = sceneLayout.positions.get(edge.callee);
        var reciprocal = pairs.has(JSON.stringify([edge.callee, edge.caller]));
        var parallel = pairs.get(JSON.stringify([edge.caller, edge.callee])) > 1;
        var bend = from === to && parallel ? (edge.kind === 'observed' ? 30 : -30)
          : reciprocal ? (parallel ? (edge.kind === 'observed' ? 22 : 48) : 30)
          : parallel ? (edge.kind === 'observed' ? 25 : -25) : 0;
        var d = edgePath(from, to, bend);
        var g = svgEl('g', { class: 'graph-edge ' + edge.kind, role: 'button', tabindex: '0', 'data-edge-id': edge.id, 'data-edge-kind': edge.kind, 'data-edge-caller': edge.caller, 'data-edge-callee': edge.callee, 'aria-label': index.byId.get(edge.caller).label + ' calls ' + index.byId.get(edge.callee).label + '. ' + (edge.kind === 'observed' ? 'Confirmed target.' : 'Declared target only.') });
        g.appendChild(svgEl('title', {}, edge.caller + ' → ' + edge.callee + '\n' + (edge.kind === 'observed' ? 'Confirmed runtime target' : 'Actual target unconfirmed') + ' · ' + edge.count + ' distinct records'));
        g.appendChild(svgEl('path', { d: d, class: 'graph-edge-hit' }));
        g.appendChild(svgEl('path', { d: d, class: 'graph-edge-line', 'marker-end': 'url(#' + mountId + '-' + edge.kind + ')' }));
        edgeLayer.appendChild(g); scene.edges.set(edge.id, g);
      });
      subgraph.nodes.forEach(function (node) {
        var p = sceneLayout.positions.get(node.id);
        var g = svgEl('g', { transform: 'translate(' + p.x + ' ' + p.y + ')', role: 'button', tabindex: '0', 'data-node-id': node.id, 'aria-label': node.id + (node.inReport ? '' : ', code unavailable') + '. Select to highlight incoming and outgoing calls; other edges remain visible.' });
        g.appendChild(svgEl('title', {}, node.id));
        g.appendChild(svgEl('rect', { x: -p.width / 2, y: -p.height / 2, width: p.width, height: p.height, rx: 7 }));
        g.appendChild(svgEl('text', { x: -p.width / 2 + 12, y: -3, class: 'graph-node-label' }, shorten(node.label, 30)));
        g.appendChild(svgEl('text', { x: -p.width / 2 + 12, y: 15, class: 'graph-node-class' }, shorten(node.className || '(default package)', 38)));
        nodeLayer.appendChild(g); scene.nodes.set(node.id, g);
      });
      return scene;
    }
    function renderGraph(refit) {
      var scene;
      if (selectedClass !== null) {
        var context = getClassContext(selectedClass);
        neighborhood = context.graph; layout = context.layout;
        if (!context.scene) context.scene = createScene(neighborhood, layout);
        scene = context.scene;
      } else {
        neighborhood = emptyNeighborhood; layout = emptyLayout;
        if (!emptyScene) emptyScene = createScene(neighborhood, layout);
        scene = emptyScene;
      }
      if (scene !== currentScene) {
        if (currentScene) svg.removeChild(currentScene.root);
        svg.appendChild(scene.root); currentScene = scene;
      }
      selectedElements = scene.nodes; edgeElements = scene.edges;
      svg.setAttribute('data-graph-mode', selectedClass === null ? 'empty' : focusId === null ? 'class' : 'focused');
      if (selectedClass === null) svg.removeAttribute('data-graph-class'); else svg.setAttribute('data-graph-class', selectedClass);
      if (focusId === null) svg.removeAttribute('data-graph-focus'); else svg.setAttribute('data-graph-focus', focusId);
      svg.setAttribute('aria-label', 'Dynamic call graph for ' + (selectedClass || 'a selected class') + '. Select a method to highlight its incoming and outgoing calls. Other edges remain visible at lower opacity. Reset graph clears the highlight.');
      scopeLabel.textContent = selectedClass === null ? 'Select a class' : (launchers.has(selectedClass) ? 'Launcher activity' : 'Class graph') + ' · ' + selectedClass;
      gestureHelp.textContent = 'Click a method to highlight its calls · Reset graph clears highlighting · drag to pan · scroll to zoom';
      gestureHelp.hidden = selectedClass === null; startMessage.hidden = selectedClass !== null;
      svg.style.display = selectedClass === null ? 'none' : ''; reset.disabled = selectedClass === null;
      if (selectedClass === null) {
        startMessage.textContent = !Array.isArray(graph.launcherActivities)
          ? 'Launcher activity metadata is unavailable. Regenerate this report from the APK, or select a class from the sidebar.'
          : !graph.launcherActivities.length ? 'No launcher activity was found in the manifest. Select a class from the sidebar.'
          : 'No dynamic calls were recorded for the manifest launcher activity: ' + graph.launcherActivities.join(', ') + '. Select another class from the sidebar.';
        status.textContent = 'No starting class selected';
      } else {
        status.textContent = 'Class graph: ' + fmt(neighborhood.classMethodCount) + ' class methods · ' + fmt(neighborhood.nodes.length - neighborhood.classMethodCount) + ' direct caller/callee methods · all ' + fmt(neighborhood.edges.length) + ' relationships';
        if (focusId !== null) status.textContent += ' · ' + fmt(activeIndex().incident.get(focusId).length) + ' highlighted; other edges faded';
      }
      if (refit !== false || !view) fit(); highlight(); renderInspector();
    }
    function updateView() {
      svg.setAttribute('viewBox', view.x + ' ' + view.y + ' ' + view.width + ' ' + view.height);
      if (svg.clientWidth && svg.clientHeight) svg.classList.toggle('is-overview', Math.min(svg.clientWidth / view.width, svg.clientHeight / view.height) * 228 < 90);
    }
    function fit() {
      if (!layout) return;
      view = { x: layout.bounds.x, y: layout.bounds.y, width: layout.bounds.width, height: layout.bounds.height }; updateView();
    }
    function zoomToNodes(ids) {
      var positions = new Map(); ids.forEach(function (id) { if (layout.positions.has(id)) positions.set(id, layout.positions.get(id)); });
      if (!positions.size) return;
      view = boundsFor(positions, []); updateView();
    }
    function point(clientX, clientY) {
      var p = svg.createSVGPoint(); p.x = clientX; p.y = clientY;
      var matrix = svg.getScreenCTM(); return matrix ? p.matrixTransform(matrix.inverse()) : { x: view.x + view.width / 2, y: view.y + view.height / 2 };
    }
    function zoom(factor, anchor) {
      if (!view) return;
      anchor = anchor || { x: view.x + view.width / 2, y: view.y + view.height / 2 };
      var aspect = svg.clientWidth / Math.max(1, svg.clientHeight);
      var maximum = Math.max(8000, layout.bounds.width * 4, layout.bounds.height * aspect * 4);
      var width = Math.max(280, Math.min(maximum, view.width / factor)), ratio = width / view.width;
      view = { x: anchor.x - (anchor.x - view.x) * ratio, y: anchor.y - (anchor.y - view.y) * ratio, width: width, height: view.height * ratio }; updateView();
    }
    var drag = null;
    // Delegated handlers keep the class scene and viewport stable during focus.
    function activateGraphTarget(event) {
      var node = event.target.closest('[data-node-id]');
      if (node) { event.preventDefault(); selectNode(node.getAttribute('data-node-id')); return true; }
      var edge = event.target.closest('[data-edge-id]');
      if (edge) { event.preventDefault(); selectEdge(edgeById.get(edge.getAttribute('data-edge-id'))); return true; }
      return false;
    }
    listen(svg, 'click', activateGraphTarget);
    listen(svg, 'wheel', function (event) { if (!event.deltaY) return; event.preventDefault(); zoom(event.deltaY < 0 ? 1.12 : 1 / 1.12, point(event.clientX, event.clientY)); }, { passive: false });
    listen(svg, 'pointerdown', function (event) {
      if (event.button !== 0 || event.target.closest('[data-node-id], .graph-edge')) return;
      drag = { id: event.pointerId, start: point(event.clientX, event.clientY) };
      try { svg.setPointerCapture(event.pointerId); } catch (error) { /* Synthetic pointers need no capture. */ }
      svg.classList.add('is-panning');
    });
    listen(svg, 'pointermove', function (event) {
      if (!drag || drag.id !== event.pointerId) return;
      var current = point(event.clientX, event.clientY); view.x += drag.start.x - current.x; view.y += drag.start.y - current.y; updateView();
    });
    function endDrag(event) { if (drag && drag.id === event.pointerId) { drag = null; svg.classList.remove('is-panning'); } }
    listen(svg, 'pointerup', endDrag); listen(svg, 'pointercancel', endDrag);
    listen(svg, 'keydown', function (event) {
      if ((event.key === 'Enter' || event.key === ' ') && activateGraphTarget(event)) return;
      if (event.target !== svg) return;
      if (event.key === '+' || event.key === '=') { event.preventDefault(); zoom(1.25); }
      else if (event.key === '-') { event.preventDefault(); zoom(1 / 1.25); }
      else if (event.key === '0') { event.preventDefault(); fit(); }
      else if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].indexOf(event.key) >= 0) {
        event.preventDefault(); view.x += (event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0) * view.width / 10;
        view.y += (event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0) * view.height / 10; updateView();
      }
    });
    listen(search, 'input', function () { searchLimit = 100; renderSearch(); });
    if (doc.defaultView) listen(doc.defaultView, 'resize', function () { if (view) updateView(); });
    renderSearch(); renderGraph();
    return { destroy: function () { disposers.forEach(function (dispose) { dispose(); }); emptyScene = null; classContext = null; currentScene = null; clear(container); } };
  }
  return { createIndex: createIndex, selectNeighborhood: selectNeighborhood, selectWholeGraph: selectWholeGraph, selectClassGraph: selectClassGraph, selectInitialClass: selectInitialClass, layoutNeighborhood: layoutNeighborhood, layoutWholeGraph: layoutWholeGraph, edgePath: edgePath, mount: mount };
}));
