package com.gab.tvnavegador

/**
 * Motor de navegacion espacial que se inyecta en cada pagina.
 *
 * En lugar de un puntero de raton, el D-pad salta directamente entre los
 * elementos accionables de la web (enlaces, botones, tarjetas, campos) igual
 * que en una aplicacion nativa de television: se resalta el elemento con foco,
 * se desplaza solo hasta ponerlo a la vista y OK lo activa.
 *
 * El script es agnostico del sitio: descubre los candidatos por selector y por
 * heuristica de estilo, y elige el destino por geometria.
 */
object SpatialNav {

    /** Se inyecta al terminar de cargar cada pagina. Es idempotente. */
    val INSTALL = """
(function () {
  if (window.__tvnav) { window.__tvnav.rescan(); return 'ready'; }

  var ACCENT = '#4FC3F7';
  var CACHE_MS = 500;
  var MAX_SCAN = 6000;
  // Por encima de esto la comparacion por pares sale cara; se omite.
  var MAX_PAIRWISE = 900;
  // Un candidato por debajo de esta fraccion del area de otro es decoracion.
  var SMALL_RATIO = 0.30;
  // Fraccion del candidato pequeno que debe caer dentro del grande.
  var INSIDE_RATIO = 0.75;
  // Solo se recurre a cursor:pointer si la web expone menos candidatos reales.
  var POINTER_SCAN_THRESHOLD = 25;

  var current = null;
  var cache = null;
  var cacheAt = 0;
  var box = null;

  // ------------------------------------------------------------- resaltado

  function overlay() {
    if (box && box.parentNode) { return box; }
    box = document.createElement('div');
    box.setAttribute('data-tvnav', 'highlight');
    var s = box.style;
    s.position = 'fixed';
    s.zIndex = '2147483647';
    s.pointerEvents = 'none';
    s.border = '3px solid ' + ACCENT;
    s.borderRadius = '6px';
    s.boxShadow = '0 0 0 2px rgba(0,0,0,.55), 0 0 14px rgba(79,195,247,.85)';
    s.transition = 'top .12s ease, left .12s ease, width .12s ease, height .12s ease';
    s.display = 'none';
    (document.body || document.documentElement).appendChild(box);
    return box;
  }

  function paint() {
    var b = overlay();
    if (!current || !inDom(current)) { b.style.display = 'none'; return; }
    var r = current.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) { b.style.display = 'none'; return; }
    b.style.display = 'block';
    b.style.top = (r.top - 2) + 'px';
    b.style.left = (r.left - 2) + 'px';
    b.style.width = (r.width) + 'px';
    b.style.height = (r.height) + 'px';
  }

  function inDom(el) {
    return el && el.isConnected !== false && document.contains(el);
  }

  // ------------------------------------------------------------ candidatos

  var SELECTOR = [
    'a[href]', 'button', 'input:not([type=hidden])', 'select', 'textarea',
    'video', 'audio', 'iframe', 'summary', 'area[href]',
    '[tabindex]:not([tabindex="-1"])', '[onclick]', '[contenteditable=true]',
    '[role=button]', '[role=link]', '[role=menuitem]', '[role=tab]',
    '[role=option]', '[role=checkbox]', '[role=radio]', '[role=switch]'
  ].join(',');

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) { return false; }
    // Fuera del documento por completo (algunos menus se ocultan asi).
    if (r.bottom < -window.innerHeight || r.top > window.innerHeight * 2) { return false; }
    var st = window.getComputedStyle(el);
    if (!st) { return false; }
    if (st.visibility === 'hidden' || st.display === 'none') { return false; }
    if (parseFloat(st.opacity || '1') < 0.05) { return false; }
    if (el.disabled) { return false; }
    return true;
  }

  var stats = { base: 0, extras: 0, suppressed: 0 };

  function measure(list) {
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      out.push({ el: list[i], r: r, a: r.width * r.height });
    }
    return out;
  }

  /**
   * Descarta los candidatos pequenos que caen dentro de otro mas grande.
   *
   * Es el caso de los botones superpuestos sobre una tarjeta (favorito, marcar
   * visto, reproducir): son muchos mas que las tarjetas y estan encima, asi
   * que sin esto acaparan el foco y la tarjeta se vuelve casi inalcanzable.
   * La comprobacion es geometrica, no de arbol, para pillar tambien los que
   * estan posicionados en absoluto fuera del contenedor.
   */
  function suppressOverlays(items) {
    if (items.length > MAX_PAIRWISE) {
      stats.suppressed = 0;
      return items.map(function (x) { return x.el; });
    }
    var keep = [];
    var dropped = 0;
    for (var i = 0; i < items.length; i++) {
      var c = items[i];
      var drop = false;
      if (c.a > 0) {
        for (var j = 0; j < items.length; j++) {
          if (i === j) { continue; }
          var p = items[j];
          if (c.a >= p.a * SMALL_RATIO) { continue; }
          var ox = Math.min(c.r.right, p.r.right) - Math.max(c.r.left, p.r.left);
          var oy = Math.min(c.r.bottom, p.r.bottom) - Math.max(c.r.top, p.r.top);
          if (ox <= 0 || oy <= 0) { continue; }
          if ((ox * oy) >= c.a * INSIDE_RATIO) { drop = true; break; }
        }
      }
      if (drop) { dropped++; } else { keep.push(c.el); }
    }
    stats.suppressed = dropped;
    return keep;
  }

  function collect() {
    var now = Date.now();
    if (cache && (now - cacheAt) < CACHE_MS) { return cache; }

    var found = [];
    var base;
    try { base = document.querySelectorAll(SELECTOR); } catch (e) { base = []; }
    for (var i = 0; i < base.length && i < MAX_SCAN; i++) {
      if (visible(base[i])) { found.push(base[i]); }
    }
    stats.base = found.length;
    stats.extras = 0;

    // La pasada por cursor:pointer obliga a un getComputedStyle por elemento,
    // que es carisimo. Solo compensa cuando la web apenas expone candidatos
    // reales, que es justo cuando las tarjetas son div con manejador de clic.
    if (found.length < POINTER_SCAN_THRESHOLD) {
      var extras = [];
      var all;
      try { all = document.querySelectorAll('div,span,li,td,article,section,figure,img'); }
      catch (e2) { all = []; }
      var limit = Math.min(all.length, MAX_SCAN);
      for (var j = 0; j < limit; j++) {
        var el = all[j];
        if (el.closest) {
          try { if (el.closest(SELECTOR)) { continue; } } catch (e3) { /* selector raro */ }
        }
        var cs = window.getComputedStyle(el);
        if (!cs || cs.cursor !== 'pointer') { continue; }
        if (!visible(el)) { continue; }
        extras.push(el);
      }
      // Entre los heuristicos, el que envuelve a otro sobra.
      for (var k = 0; k < extras.length; k++) {
        var e = extras[k];
        var wraps = false;
        for (var m = 0; m < extras.length; m++) {
          if (m !== k && e.contains(extras[m])) { wraps = true; break; }
        }
        if (!wraps) { found.push(e); }
      }
      stats.extras = found.length - stats.base;
    }

    cache = suppressOverlays(measure(found));
    cacheAt = now;
    return cache;
  }

  /** Datos de lo que ve el motor en esta pagina, para poder afinarlo. */
  function diagnose() {
    var list = collect();
    var info = {
      total: list.length,
      base: stats.base,
      extras: stats.extras,
      suppressed: stats.suppressed,
      focused: null,
      tags: {}
    };
    for (var i = 0; i < list.length; i++) {
      var t = (list[i].tagName || '?').toLowerCase();
      info.tags[t] = (info.tags[t] || 0) + 1;
    }
    if (current && inDom(current)) {
      var r = current.getBoundingClientRect();
      var cls = '';
      try { cls = (current.className || '').toString().slice(0, 80); } catch (e) {}
      info.focused = {
        tag: (current.tagName || '?').toLowerCase(),
        id: current.id || '',
        cls: cls,
        w: Math.round(r.width),
        h: Math.round(r.height)
      };
    }
    return JSON.stringify(info);
  }

  // ------------------------------------------------------------- geometria

  function pick(dir) {
    var list = collect();
    if (!list.length) { return null; }

    if (!current || !inDom(current)) {
      // Primer salto: el candidato mas arriba y a la izquierda que se vea.
      var best0 = null, bs0 = Infinity;
      for (var i = 0; i < list.length; i++) {
        var r0 = list[i].getBoundingClientRect();
        if (r0.bottom < 0 || r0.top > window.innerHeight) { continue; }
        var s0 = r0.top * 2 + r0.left;
        if (s0 < bs0) { bs0 = s0; best0 = list[i]; }
      }
      return best0 || list[0];
    }

    var a = current.getBoundingClientRect();
    var best = null, bestScore = Infinity;
    var TOL = 6;

    for (var n = 0; n < list.length; n++) {
      var cand = list[n];
      if (cand === current) { continue; }
      var b = cand.getBoundingClientRect();

      var primary, orth, overlap;
      if (dir === 'right') {
        primary = b.left - a.right;
        orth = Math.abs((b.top + b.height / 2) - (a.top + a.height / 2));
        overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      } else if (dir === 'left') {
        primary = a.left - b.right;
        orth = Math.abs((b.top + b.height / 2) - (a.top + a.height / 2));
        overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      } else if (dir === 'down') {
        primary = b.top - a.bottom;
        orth = Math.abs((b.left + b.width / 2) - (a.left + a.width / 2));
        overlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      } else {
        primary = a.top - b.bottom;
        orth = Math.abs((b.left + b.width / 2) - (a.left + a.width / 2));
        overlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      }

      if (primary < -TOL) { continue; }
      if (primary < 0) { primary = 0; }

      // Alineado en el eje perpendicular: se prefiere con mucha diferencia,
      // que es lo que hace que las rejillas de caratulas se recorran bien.
      var score = (overlap > 0)
        ? primary + orth * 0.4
        : primary + orth * 3 + 300;

      if (score < bestScore) { bestScore = score; best = cand; }
    }
    return best;
  }

  function reveal(el, dir) {
    var r = el.getBoundingClientRect();
    var fully = r.top >= 0 && r.left >= 0 &&
                r.bottom <= window.innerHeight && r.right <= window.innerWidth;
    if (fully) { return; }
    // Al avanzar en horizontal se centra tambien en ese eje, que es lo que
    // hace que los carruseles de caratulas se recorran bien.
    var inline = (dir === 'left' || dir === 'right') ? 'center' : 'nearest';
    try {
      el.scrollIntoView({ block: 'center', inline: inline, behavior: 'smooth' });
    } catch (e) {
      el.scrollIntoView();
    }
  }

  function focus(el, dir) {
    current = el;
    try {
      if (el.focus) { el.focus({ preventScroll: true }); }
    } catch (e) {
      try { el.focus(); } catch (e2) { /* algunos nodos no son enfocables */ }
    }
    reveal(el, dir);
    paint();
    // El desplazamiento suave mueve el rectangulo: se repinta al asentarse.
    setTimeout(paint, 160);
    setTimeout(paint, 380);
  }

  // ------------------------------------------------------------- acciones

  function move(dir) {
    var next = pick(dir);
    if (next) { focus(next, dir); return 'moved'; }

    // Sin candidato en esa direccion: se desplaza la pagina, para que las
    // paginas largas sigan siendo recorribles.
    var dy = (dir === 'down') ? 1 : (dir === 'up' ? -1 : 0);
    var dx = (dir === 'right') ? 1 : (dir === 'left' ? -1 : 0);
    var amount = dy !== 0 ? window.innerHeight * 0.8 : window.innerWidth * 0.8;
    try {
      window.scrollBy({ top: dy * amount, left: dx * amount, behavior: 'smooth' });
    } catch (e) {
      window.scrollBy(dx * amount, dy * amount);
    }
    cache = null;
    setTimeout(paint, 220);
    return 'scrolled';
  }

  function fire(el, type) {
    var r = el.getBoundingClientRect();
    var init = {
      bubbles: true, cancelable: true, view: window,
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2,
      button: 0, buttons: (type === 'mouseup' || type === 'click') ? 0 : 1
    };
    var ev = null;
    if (type.indexOf('pointer') === 0 && window.PointerEvent) {
      init.pointerId = 1; init.pointerType = 'touch'; init.isPrimary = true;
      try { ev = new PointerEvent(type, init); } catch (e) { ev = null; }
    }
    if (!ev) {
      try { ev = new MouseEvent(type, init); }
      catch (e2) {
        ev = document.createEvent('MouseEvents');
        ev.initEvent(type, true, true);
      }
    }
    el.dispatchEvent(ev);
  }

  function activate() {
    if (!current || !inDom(current)) { return 'none'; }
    var el = current;
    var tag = (el.tagName || '').toLowerCase();
    var type = (el.getAttribute && (el.getAttribute('type') || '')).toLowerCase();

    // Campos de texto: basta con enfocarlos para que salga el teclado de la TV.
    if (tag === 'input' && ['text', 'search', 'email', 'url', 'tel', 'password', 'number', ''].indexOf(type) !== -1) {
      el.focus(); return 'text';
    }
    if (tag === 'textarea' || el.isContentEditable) { el.focus(); return 'text'; }
    if (tag === 'select') { el.focus(); return 'select'; }
    if (tag === 'iframe') { try { el.focus(); } catch (e) {} return 'iframe'; }

    // Secuencia completa: hay webs que escuchan pointer, otras mouse y otras click.
    try { fire(el, 'pointerdown'); } catch (e) {}
    try { fire(el, 'mousedown'); } catch (e) {}
    try { el.focus({ preventScroll: true }); } catch (e) {}
    try { fire(el, 'pointerup'); } catch (e) {}
    try { fire(el, 'mouseup'); } catch (e) {}
    try { fire(el, 'click'); } catch (e) {}
    if (typeof el.click === 'function') {
      try { el.click(); } catch (e) {}
    }
    cache = null;
    return 'clicked';
  }

  function rescan() { cache = null; paint(); }

  function clear() {
    current = null;
    if (box) { box.style.display = 'none'; }
  }

  window.addEventListener('scroll', paint, true);
  window.addEventListener('resize', function () { cache = null; paint(); }, true);

  window.__tvnav = {
    move: move,
    activate: activate,
    rescan: rescan,
    clear: clear,
    diagnose: diagnose
  };
  return 'installed';
})();
"""

    fun move(direction: String) = "window.__tvnav && window.__tvnav.move('$direction');"

    const val ACTIVATE = "window.__tvnav && window.__tvnav.activate();"
    const val CLEAR = "window.__tvnav && window.__tvnav.clear();"

    /** Devuelve un JSON con lo que el motor ve en la pagina actual. */
    const val DIAGNOSE = "window.__tvnav ? window.__tvnav.diagnose() : '{}';"
}
