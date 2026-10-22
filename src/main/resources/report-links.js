(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.AndroLogCodeLinks = api;
}(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  // Fixed lexical patterns; report classes are looked up in maps, never in a
  // generated alternation or a scan of every class for each statement.
  var namePattern = /[$_\p{ID_Start}][$_\p{ID_Continue}]*(?:\.[$_\p{ID_Start}][$_\p{ID_Continue}]*)*/uy;
  var namePart = /[$_\p{ID_Continue}]/u;

  function createIndex(classes) {
    var index = { classes: new Map(), methods: new Map() };
    (Array.isArray(classes) ? classes : []).forEach(function (clazz) {
      if (!clazz || typeof clazz.name !== 'string') return;
      index.classes.set(clazz.name, true);
      (Array.isArray(clazz.methods) ? clazz.methods : []).forEach(function (method) {
        if (method && typeof method.signature === 'string') {
          index.methods.set(method.signature, clazz.name);
        }
      });
    });
    return index;
  }

  function href(className, methodSignature) {
    var result = '#class=' + encodeURIComponent(className);
    if (methodSignature !== undefined && methodSignature !== null && methodSignature !== '') {
      result += '&method=' + encodeURIComponent(methodSignature);
    }
    return result;
  }

  function readName(text, start) {
    namePattern.lastIndex = start;
    var match = namePattern.exec(text);
    return match ? { name: match[0], end: namePattern.lastIndex } : null;
  }

  function previousCharacter(text, start) {
    if (!start) return '';
    var offset = start - 1;
    var code = text.charCodeAt(offset);
    if (code >= 0xdc00 && code <= 0xdfff && offset > 0) offset--;
    return text.slice(offset, start);
  }

  function isNameBoundary(text, start, end) {
    var before = previousCharacter(text, start);
    var after = end < text.length ? String.fromCodePoint(text.codePointAt(end)) : '';
    return before !== '.' && after !== '.' && !namePart.test(before) && !namePart.test(after);
  }

  function skipQuoted(text, start) {
    var quote = text.charAt(start);
    var offset = start + 1;
    while (offset < text.length) {
      var character = text.charAt(offset++);
      if (character === '\\') offset++;
      else if (character === quote) break;
    }
    return Math.min(offset, text.length);
  }

  function referenceEnd(text, start) {
    var owner = readName(text, start + 1);
    if (!owner || text.charAt(owner.end) !== ':') return -1;
    var offset = owner.end + 1;
    while (offset < text.length) {
      var character = text.charAt(offset);
      if (character === '>') return offset + 1;
      if (character === '<') {
        // Constructor names contain their own angle brackets inside the full
        // method signature. Other nested references are not valid Jimple types.
        if (text.slice(offset, offset + 6) === '<init>') offset += 6;
        else if (text.slice(offset, offset + 8) === '<clinit>') offset += 8;
        else return -1;
      } else if (character === '"' || character === "'") {
        offset = skipQuoted(text, offset);
      } else if (character === '\n' || character === '\r') {
        return -1;
      } else {
        offset++;
      }
    }
    return -1;
  }

  function isDefaultPackageType(text, start, end) {
    if (text.charAt(start - 1) === '<' && text.charAt(end) === ':') return true;
    // A default-package class may share a name with a local variable. Restrict
    // these otherwise ambiguous bare identifiers to explicit type contexts.
    var before = text.slice(Math.max(0, start - 96), start);
    if (/\b(?:new|instanceof|extends|implements|class|interface)\s*$/.test(before)) return true;
    if (/\b(?:newarray|newmultiarray)\s*\(\s*$/.test(before)) return true;
    if (/@(?:this|parameter\d+)\s*:\s*$/.test(before)) return true;
    // A cast has an operand after its closing parenthesis. A sole invocation
    // argument such as call(Widget) is a variable, not evidence of a type use.
    return /(?:^|[=,(])\s*\(\s*$/.test(before)
      && /^(?:\s*\[\s*\])*\s*\)\s*[$_\p{ID_Start}\d"']/u.test(text.slice(end));
  }

  /** Returns plain {text} tokens or {text, className, methodSignature?} targets. */
  function tokenize(value, index) {
    var text = value === null || value === undefined ? '' : String(value);
    var tokens = [];
    if (!index || !index.classes || !index.methods) return text ? [{ text: text }] : tokens;
    var offset = 0;
    var plainStart = 0;

    function target(end, className, methodSignature) {
      if (plainStart < offset) tokens.push({ text: text.slice(plainStart, offset) });
      var token = { text: text.slice(offset, end), className: className };
      if (methodSignature !== undefined) token.methodSignature = methodSignature;
      tokens.push(token);
      offset = end;
      plainStart = end;
    }

    while (offset < text.length) {
      var character = text.charAt(offset);
      if (character === '"' || character === "'") {
        offset = skipQuoted(text, offset);
        continue;
      }
      if (character === '<') {
        var end = referenceEnd(text, offset);
        if (end >= 0) {
          var signature = text.slice(offset, end);
          var owner = index.methods.get(signature);
          if (owner !== undefined) {
            target(end, owner, signature);
            continue;
          }
        }
      }
      var name = readName(text, offset);
      if (name) {
        if (index.classes.has(name.name) && isNameBoundary(text, offset, name.end)
            && (name.name.indexOf('.') >= 0 || isDefaultPackageType(text, offset, name.end))) {
          target(name.end, name.name);
        } else {
          offset = name.end;
        }
      } else {
        offset += text.codePointAt(offset) > 0xffff ? 2 : 1;
      }
    }
    if (plainStart < text.length) tokens.push({ text: text.slice(plainStart) });
    return tokens;
  }

  function appendCode(container, text, index) {
    var doc = container.ownerDocument;
    tokenize(text, index).forEach(function (token) {
      if (token.className === undefined) {
        container.appendChild(doc.createTextNode(token.text));
        return;
      }
      var anchor = doc.createElement('a');
      anchor.className = 'code-link';
      anchor.textContent = token.text;
      anchor.setAttribute('href', href(token.className, token.methodSignature));
      anchor.setAttribute('data-code-class', token.className);
      if (token.methodSignature !== undefined) anchor.setAttribute('data-code-method', token.methodSignature);
      container.appendChild(anchor);
    });
  }

  return { createIndex: createIndex, tokenize: tokenize, appendCode: appendCode, href: href };
}));
