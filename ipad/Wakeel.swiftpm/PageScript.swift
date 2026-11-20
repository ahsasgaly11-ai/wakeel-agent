import Foundation

/// The script the agent's browser runs inside every page (in its own JavaScript world, so the page cannot
/// see or change it). It lists what can be acted on, acts on it, and — while the owner teaches a task —
/// reports what the owner does.
enum PageScript {
    static let source = #"""
(function () {
  if (window.__wk) return;
  const W = { map: new Map(), seq: 0, lastMut: Date.now(), recording: false };
  try {
    new MutationObserver(function () { W.lastMut = Date.now(); })
      .observe(document.documentElement, { subtree: true, childList: true, attributes: true, characterData: true });
  } catch (e) {}

  const SEL = 'a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],' +
    '[role=checkbox],[role=radio],[role=switch],[role=combobox],[role=textbox],[role=searchbox],[contenteditable=""],[contenteditable=true],[onclick]';

  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function norm(s) {
    return clean(s).toLowerCase().replace(/[ً-ْٰـ]/g, '').replace(/[أإآٱ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي');
  }
  function shown(el) {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const st = getComputedStyle(el);
    return st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0';
  }
  function isEdit(el) {
    const t = el.tagName;
    if (t === 'TEXTAREA') return true;
    if (t === 'INPUT') {
      const ty = (el.type || 'text').toLowerCase();
      return ['button', 'submit', 'reset', 'checkbox', 'radio', 'file', 'image', 'range', 'color', 'hidden'].indexOf(ty) < 0;
    }
    return el.isContentEditable || ['textbox', 'searchbox', 'combobox'].indexOf(el.getAttribute('role')) >= 0;
  }
  function labelOf(el) {
    let s = el.getAttribute('aria-label') || '';
    const by = el.getAttribute('aria-labelledby');
    if (!s && by) s = by.split(/\s+/).map(function (id) { const n = document.getElementById(id); return n ? n.innerText : ''; }).join(' ');
    const t = el.tagName;
    if (!clean(s) && (t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT')) {
      const ty = (el.type || '').toLowerCase();
      if (ty === 'submit' || ty === 'button' || ty === 'reset') s = el.value;
      if (!clean(s) && el.id) { try { const l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (l) s = l.innerText; } catch (e) {} }
      if (!clean(s)) { const l = el.closest('label'); if (l) s = l.innerText; }
      if (!clean(s)) s = el.placeholder || el.title || el.name || '';
    }
    if (!clean(s) && t !== 'INPUT' && t !== 'TEXTAREA' && t !== 'SELECT' && !el.isContentEditable) s = el.innerText || el.textContent || '';
    if (!clean(s) && el.querySelector) {
      const img = el.querySelector('img[alt],svg[aria-label],[title]');
      if (img) s = img.getAttribute('alt') || img.getAttribute('aria-label') || img.getAttribute('title') || '';
    }
    if (!clean(s)) s = el.title || el.getAttribute('placeholder') || el.getAttribute('data-placeholder') || el.getAttribute('data-tooltip') || '';
    return clean(s).slice(0, 90);
  }
  function roleOf(el) {
    const r = el.getAttribute('role');
    if (r) return r === 'textbox' || r === 'searchbox' || r === 'combobox' ? 'edit' : r;
    const t = el.tagName;
    if (t === 'A') return 'link';
    if (t === 'BUTTON' || t === 'SUMMARY') return 'button';
    if (t === 'SELECT') return 'select';
    if (t === 'TEXTAREA') return 'edit';
    if (t === 'INPUT') {
      const ty = (el.type || 'text').toLowerCase();
      if (ty === 'checkbox' || ty === 'radio') return ty;
      if (ty === 'submit' || ty === 'button' || ty === 'reset' || ty === 'image') return 'button';
      return 'edit';
    }
    return el.isContentEditable ? 'edit' : 'item';
  }
  function selOf(el) {
    try {
      if (el.id && document.querySelectorAll('#' + CSS.escape(el.id)).length === 1) return '#' + CSS.escape(el.id);
      const n = el.getAttribute('name');
      if (n) return el.tagName.toLowerCase() + '[name="' + n.replace(/"/g, '\\"') + '"]';
      const t = el.getAttribute('data-testid');
      if (t) return '[data-testid="' + t.replace(/"/g, '\\"') + '"]';
    } catch (e) {}
    return '';
  }
  function valueOf(el) {
    const t = el.tagName;
    if (t === 'INPUT') {
      const ty = (el.type || 'text').toLowerCase();
      if (ty === 'password') return el.value ? '(hidden)' : '';
      if (ty === 'checkbox' || ty === 'radio' || ty === 'submit' || ty === 'button') return '';
      return clean(el.value).slice(0, 80);
    }
    if (t === 'TEXTAREA') return clean(el.value).slice(0, 80);
    if (t === 'SELECT') { const o = el.options[el.selectedIndex]; return o ? clean(o.text).slice(0, 60) : ''; }
    if (el.isContentEditable) return clean(el.innerText).slice(0, 80);
    return '';
  }
  function flagsOf(el) {
    let f = '';
    if (isEdit(el)) f += 'e';
    if ((el.type || '').toLowerCase() === 'password') f += 'p';
    if (el.checked === true || el.getAttribute('aria-checked') === 'true' || el.getAttribute('aria-pressed') === 'true') f += 'c';
    if (el.getAttribute('aria-selected') === 'true' || el.getAttribute('aria-current')) f += 's';
    if (el.disabled === true || el.getAttribute('aria-disabled') === 'true') f += 'd';
    return f;
  }
  function gather(root, out) {
    const list = root.querySelectorAll('*');
    for (let i = 0; i < list.length; i++) {
      const el = list[i];
      if (el.matches(SEL)) out.push(el);
      if (el.shadowRoot) gather(el.shadowRoot, out);
      if (el.tagName === 'IFRAME') { try { if (el.contentDocument && el.contentDocument.body) gather(el.contentDocument, out); } catch (e) {} }
    }
  }
  function candidates() {
    const out = [];
    gather(document, out);
    // Things that are clickable only because a script says so: the top-most element with a pointer cursor
    const extra = document.querySelectorAll('div,span,li,label,img,td');
    if (extra.length < 5000) {
      const vh = window.innerHeight;
      for (let i = 0; i < extra.length; i++) {
        const el = extra[i];
        const r = el.getBoundingClientRect();
        if (r.bottom < -50 || r.top > vh + 50 || r.width < 8 || r.height < 8) continue;
        if (getComputedStyle(el).cursor !== 'pointer') continue;
        if (el.parentElement && getComputedStyle(el.parentElement).cursor === 'pointer') continue;
        if (el.closest(SEL) || el.querySelector(SEL)) continue;
        out.push(el);
      }
    }
    return out;
  }
  function keep(el) { W.seq += 1; W.map.set(W.seq, el); return W.seq; }

  W.quiet = function () { return Date.now() - W.lastMut; };

  W.snap = function () {
    W.map = new Map();
    W.seq = 0;
    const vh = window.innerHeight;
    const els = [];
    let below = 0, above = 0;
    const seen = new Set();
    const all = candidates();
    for (let i = 0; i < all.length; i++) {
      const el = all[i];
      if (seen.has(el) || !shown(el)) continue;
      seen.add(el);
      const r = el.getBoundingClientRect();
      if (r.top > vh + 60) { below++; continue; }
      if (r.bottom < -60) { above++; continue; }
      const label = labelOf(el);
      const edit = isEdit(el);
      if (!label && !edit && el.tagName !== 'SELECT' && (el.type || '') !== 'checkbox' && (el.type || '') !== 'radio') continue;
      if (els.length >= 140) { below++; continue; }
      els.push([keep(el), roleOf(el), label, valueOf(el), flagsOf(el), selOf(el)]);
    }
    // What the page says, where the owner would be looking
    let text = '';
    try {
      const tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      let node;
      while ((node = tw.nextNode()) && text.length < 1600) {
        const s = clean(node.nodeValue);
        if (s.length < 2) continue;
        const p = node.parentElement;
        if (!p || /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/.test(p.tagName)) continue;
        const r = p.getBoundingClientRect();
        if (r.bottom < 0 || r.top > vh || r.width < 2 || r.height < 2) continue;
        if (getComputedStyle(p).visibility === 'hidden') continue;
        text += s + ' · ';
      }
    } catch (e) {}
    const max = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
    return JSON.stringify({ url: location.href, title: document.title, sy: Math.round(window.scrollY), max: max, vh: vh, below: below, above: above, els: els, text: text.slice(0, 1600) });
  };

  W.text = function () {
    const s = (document.body ? document.body.innerText : '').replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n').trim();
    return s.slice(0, 14000);
  };

  W.find = function (label, wantEdit) {
    const raw = String(label || '');
    try {
      if (/^[#\[.]/.test(raw) || /^[a-z]+\[/.test(raw)) { const q = document.querySelector(raw); if (q) return keep(q); }
    } catch (e) {}
    const n = norm(raw);
    if (!n) return -1;
    let best = null, score = 0;
    const all = candidates();
    for (let i = 0; i < all.length; i++) {
      const el = all[i];
      const edit = isEdit(el);
      if (wantEdit && !edit) continue;
      let l = norm(labelOf(el));
      let s = 0;
      if (l === n) s = 100; else if (l && l.indexOf(n) === 0) s = 70; else if (l && l.indexOf(n) >= 0) s = 50; else if (l.length >= 3 && n.indexOf(l) >= 0) s = 30;
      if (!s && edit) {
        const more = norm((el.name || '') + ' ' + (el.id || '') + ' ' + (el.getAttribute('placeholder') || ''));
        if (more.indexOf(n) >= 0) s = 40;
      }
      if (!s) continue;
      if (shown(el)) s += 5; else s -= 25;
      if (s > score) { score = s; best = el; }
    }
    return best && score > 20 ? keep(best) : -1;
  };

  W.info = function (i) {
    const el = W.map.get(i);
    if (!el || !el.isConnected) return '';
    return JSON.stringify({ role: roleOf(el), label: labelOf(el), value: valueOf(el), flags: flagsOf(el), sel: selOf(el) });
  };

  W.tap = function (i) {
    const el = W.map.get(i);
    if (!el || !el.isConnected) return 'gone';
    try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (e) {}
    const r = el.getBoundingClientRect();
    const o = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    try {
      el.dispatchEvent(new PointerEvent('pointerover', o)); el.dispatchEvent(new MouseEvent('mouseover', o));
      el.dispatchEvent(new PointerEvent('pointerdown', o)); el.dispatchEvent(new MouseEvent('mousedown', o));
      if (el.focus) el.focus({ preventScroll: true });
      el.dispatchEvent(new PointerEvent('pointerup', o)); el.dispatchEvent(new MouseEvent('mouseup', o));
    } catch (e) {}
    if (typeof el.click === 'function') el.click(); else el.dispatchEvent(new MouseEvent('click', o));
    return 'ok';
  };

  W.type = function (i, text) {
    const el = W.map.get(i);
    if (!el || !el.isConnected) return JSON.stringify({ ok: false, why: 'gone' });
    if ((el.type || '').toLowerCase() === 'password') return JSON.stringify({ ok: false, why: 'password' });
    try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
    if (el.focus) el.focus({ preventScroll: true });
    const t = el.tagName;
    if (t === 'INPUT' || t === 'TEXTAREA') {
      const proto = t === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
      el.__wkLast = text;
      el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return JSON.stringify({ ok: clean(el.value) === clean(text), now: String(el.value).slice(0, 200) });
    }
    const sel = window.getSelection();
    const range = document.createRange();
    range.selectNodeContents(el);
    sel.removeAllRanges();
    sel.addRange(range);
    let done = false;
    try { done = document.execCommand('insertText', false, text); } catch (e) {}
    if (!done || clean(el.innerText) !== clean(text)) {
      el.textContent = text;
      el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
    }
    return JSON.stringify({ ok: clean(el.innerText) === clean(text), now: clean(el.innerText).slice(0, 200) });
  };

  W.enter = function (i) {
    const el = i > 0 ? W.map.get(i) : document.activeElement;
    if (!el || !el.isConnected) return 'gone';
    const o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    const go = el.dispatchEvent(new KeyboardEvent('keydown', o));
    el.dispatchEvent(new KeyboardEvent('keypress', o));
    if (go && el.tagName === 'INPUT' && el.form) {
      if (typeof el.form.requestSubmit === 'function') el.form.requestSubmit(); else el.form.submit();
    }
    el.dispatchEvent(new KeyboardEvent('keyup', o));
    return 'ok';
  };

  W.select = function (i, text) {
    const el = W.map.get(i);
    if (!el || el.tagName !== 'SELECT') return 'gone';
    const n = norm(text);
    let pick = -1;
    for (let k = 0; k < el.options.length; k++) {
      const t = norm(el.options[k].text), v = norm(el.options[k].value);
      if (t === n || v === n) { pick = k; break; }
      if (pick < 0 && n && t.indexOf(n) >= 0) pick = k;
    }
    if (pick < 0) return 'no option';
    el.selectedIndex = pick;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return 'ok';
  };

  W.scroll = function (down) {
    const step = Math.round(window.innerHeight * 0.85) * (down ? 1 : -1);
    const before = window.scrollY;
    window.scrollBy(0, step);
    if (Math.abs(window.scrollY - before) > 2) return true;
    // A page that scrolls inside a panel rather than as a whole
    let best = null, area = 0;
    const list = document.querySelectorAll('div,main,section,ul,ol,article,aside');
    for (let i = 0; i < list.length && i < 4000; i++) {
      const el = list[i];
      if (el.scrollHeight <= el.clientHeight + 20) continue;
      const oy = getComputedStyle(el).overflowY;
      if (oy !== 'auto' && oy !== 'scroll') continue;
      const r = el.getBoundingClientRect();
      const a = r.width * r.height;
      if (a > area) { area = a; best = el; }
    }
    if (!best) return false;
    const b = best.scrollTop;
    best.scrollTop = b + step;
    return Math.abs(best.scrollTop - b) > 2;
  };

  // ---- teaching: the owner acts, the page reports
  function post(o) { try { window.webkit.messageHandlers.wk.postMessage(JSON.stringify(o)); } catch (e) {} }
  function typed(el) {
    if ((el.type || '').toLowerCase() === 'password') { post({ k: 'secret' }); return; }
    const v = el.isContentEditable ? clean(el.innerText) : String(el.value || '');
    if (el.__wkLast === v) return;
    el.__wkLast = v;
    post({ k: el.tagName === 'SELECT' ? 'select' : 'type', label: labelOf(el), sel: selOf(el), text: el.tagName === 'SELECT' ? valueOf(el) : v });
  }
  document.addEventListener('click', function (e) {
    if (!W.recording || !(e.isTrusted || W.trustAll)) return;
    let el = e.target;
    if (!(el instanceof Element)) return;
    el = el.closest(SEL) || el;
    if (isEdit(el) || el.tagName === 'SELECT') return;
    if ((el.type || '') === 'checkbox' || (el.type || '') === 'radio' || labelOf(el)) post({ k: 'tap', label: labelOf(el), sel: selOf(el), role: roleOf(el) });
  }, true);
  document.addEventListener('change', function (e) {
    if (!W.recording || !(e.target instanceof Element)) return;
    const el = e.target;
    if (isEdit(el) || el.tagName === 'SELECT') typed(el);
  }, true);
  document.addEventListener('focusout', function (e) {
    if (!W.recording || !(e.target instanceof Element)) return;
    if (e.target.isContentEditable) typed(e.target);
  }, true);
  document.addEventListener('keydown', function (e) {
    if (!W.recording || e.key !== 'Enter' || !(e.isTrusted || W.trustAll) || !(e.target instanceof Element)) return;
    const el = e.target;
    if (!isEdit(el) || el.tagName === 'TEXTAREA') return;
    typed(el);
    post({ k: 'enter', label: labelOf(el), sel: selOf(el) });
  }, true);
  W.rec = function (on) { W.recording = !!on; return W.recording; };

  // ---- the app's own keyboard typing into the page, in the owner's place
  function focused() {
    let el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
    if (el && el.tagName === 'IFRAME') { try { el = el.contentDocument.activeElement; } catch (e) {} }
    return el && isEdit(el) ? el : null;
  }
  function setValue(el, v) {
    const proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
  }
  function keyEvent(el, type, key) {
    const code = key === 'Enter' ? 13 : key === 'Backspace' ? 8 : (key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0);
    return el.dispatchEvent(new KeyboardEvent(type, { key: key, code: key.length === 1 ? '' : key, keyCode: code, which: code, bubbles: true, cancelable: true }));
  }
  W.key = function (text) {
    const el = focused();
    if (!el) return false;
    const first = Array.from(text)[0] || '';
    keyEvent(el, 'keydown', first);
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
      const v = String(el.value);
      let s = null, e = null;
      try { s = el.selectionStart; e = el.selectionEnd; } catch (x) {}
      if (s === null || e === null) { s = v.length; e = v.length; }       // e-mail and number fields hide the caret
      setValue(el, v.slice(0, s) + text + v.slice(e));
      try { el.setSelectionRange(s + text.length, s + text.length); } catch (x) {}
      el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
    } else {
      let done = false;
      try { done = document.execCommand('insertText', false, text); } catch (x) {}
      if (!done) { el.textContent = (el.textContent || '') + text; el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text })); }
    }
    keyEvent(el, 'keyup', first);
    return true;
  };
  W.del = function () {
    const el = focused();
    if (!el) return false;
    if (!keyEvent(el, 'keydown', 'Backspace')) return true;
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
      const v = String(el.value);
      let s = null, e = null;
      try { s = el.selectionStart; e = el.selectionEnd; } catch (x) {}
      if (s === null || e === null) { s = v.length; e = v.length; }
      if (s === e) {
        if (s === 0) return true;
        // one whole character, also when it is stored as a pair
        const cut = s >= 2 && v.charCodeAt(s - 1) >= 0xDC00 && v.charCodeAt(s - 1) <= 0xDFFF ? 2 : 1;
        s -= cut;
      }
      setValue(el, v.slice(0, s) + v.slice(e));
      try { el.setSelectionRange(s, s); } catch (x) {}
      el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContentBackward' }));
    } else {
      let done = false;
      try { done = document.execCommand('delete', false, null); } catch (x) {}
      if (!done) { const t = Array.from(el.textContent || ''); t.pop(); el.textContent = t.join(''); el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContentBackward' })); }
    }
    keyEvent(el, 'keyup', 'Backspace');
    return true;
  };
  W.enterKey = function () {
    const el = focused();
    if (!el) return false;
    if (el.tagName === 'INPUT') return W.enter(0) === 'ok';
    // A message box or a text area: the page decides (send, or a new line)
    const go = keyEvent(el, 'keydown', 'Enter');
    el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
    if (go) {
      if (el.tagName === 'TEXTAREA') W.key('\n');
      else { try { document.execCommand('insertLineBreak', false, null); } catch (x) {} }
    }
    keyEvent(el, 'keyup', 'Enter');
    return true;
  };
  document.addEventListener('focusin', function (e) {
    if (e.target instanceof Element && isEdit(e.target)) post({ k: 'focus', on: true, secure: (e.target.type || '').toLowerCase() === 'password', kind: (e.target.type || e.target.inputMode || '').toLowerCase() });
  }, true);
  document.addEventListener('focusout', function (e) {
    if (e.target instanceof Element && isEdit(e.target)) post({ k: 'focus', on: false });
  }, true);

  window.__wk = W;
})();
"""#
}
