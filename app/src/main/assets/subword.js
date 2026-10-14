// 注入 m.youtube.com：把画面上字幕里的英文词变成可以点的。点一个词：暂停视频、高亮这个词，
// 把词、整行字幕、前一行字幕和词的位置交给 App（SubWordBridge.lookup）。App 收起释义时调 __subwordResume()。
// YouTube 是单页应用，页面里切换视频不会重新加载，所以只注入一次（重复注入直接返回）。
(function () {
  if (window.__subwordInstalled) return;
  window.__subwordInstalled = true;

  var SEGMENT = '.ytp-caption-segment';

  var style = document.createElement('style');
  style.textContent =
    SEGMENT + '{pointer-events:auto !important;-webkit-user-select:none;user-select:none}' +
    '.sw-w{pointer-events:auto !important;border-radius:3px}' +
    '.sw-w.sw-on{background:#FFD54F !important;color:#000 !important}';
  (document.head || document.documentElement).appendChild(style);

  // 最近几行字幕，给 AI 当上下文。自动生成的字幕是一个词一个词往外冒的，
  // 新的一行以上一行开头时只是同一行在变长，替换掉而不是另记一行
  var history = [];
  function remember(line) {
    if (!line) return;
    var last = history[history.length - 1];
    if (last === line) return;
    if (last && (line.indexOf(last) === 0 || last.indexOf(line) === 0)) {
      history[history.length - 1] = line.length >= last.length ? line : last;
      return;
    }
    history.push(line);
    if (history.length > 6) history.shift();
  }

  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }

  function currentLine() {
    var parts = [];
    document.querySelectorAll(SEGMENT).forEach(function (s) { parts.push(s.textContent); });
    return clean(parts.join(' '));
  }

  // 把一段字幕的文字拆成「词」和「词之间的东西」，词包在 span.sw-w 里。文字不变，YouTube 换字幕时会整个改掉
  var WORD = /[A-Za-z0-9][A-Za-z0-9'’\-]*/g;
  function wrap(seg) {
    var text = seg.textContent;
    if (seg.__swText === text && seg.querySelector('.sw-w')) return;
    var frag = document.createDocumentFragment();
    var last = 0, m;
    WORD.lastIndex = 0;
    while ((m = WORD.exec(text))) {
      if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
      var span = document.createElement('span');
      span.className = 'sw-w';
      span.textContent = m[0];
      frag.appendChild(span);
      last = m.index + m[0].length;
    }
    if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
    seg.textContent = '';
    seg.appendChild(frag);
    seg.__swText = text;
  }

  // 每 150 毫秒看一次字幕：比监听整个页面的改动省事，YouTube 页面改动很频繁
  setInterval(function () {
    var segs = document.querySelectorAll(SEGMENT);
    if (!segs.length) return;
    segs.forEach(wrap);
    remember(currentLine());
  }, 150);

  // 点到字幕里的词：在 window 的捕获阶段就截下来，播放器收不到，不会当成「点视频」去暂停或显示控制条
  var start = null, lastLookup = 0;
  function wordAt(e) { return e.target && e.target.closest ? e.target.closest('.sw-w') : null; }

  window.addEventListener('touchstart', function (e) {
    var w = wordAt(e);
    if (!w) { start = null; return; }
    var t = e.touches[0];
    start = { w: w, x: t.clientX, y: t.clientY };
    e.stopImmediatePropagation();
  }, { capture: true, passive: true });

  window.addEventListener('touchend', function (e) {
    var w = wordAt(e);
    if (!w) return;
    e.stopImmediatePropagation();
    if (e.cancelable) e.preventDefault();   // 不再生成 click，播放器不会再收到一次
    var t = e.changedTouches[0];
    if (start && start.w === w && Math.abs(t.clientX - start.x) < 12 && Math.abs(t.clientY - start.y) < 12) lookup(w);
    start = null;
  }, { capture: true, passive: false });

  ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'click', 'dblclick'].forEach(function (type) {
    window.addEventListener(type, function (e) {
      var w = wordAt(e);
      if (!w) return;
      e.stopImmediatePropagation();
      if (e.cancelable && type !== 'pointerdown') e.preventDefault();
      // 鼠标或触控笔（没有 touch 事件）的点击
      if (type === 'click' && Date.now() - lastLookup > 600) lookup(w);
    }, { capture: true, passive: false });
  });

  var resumeVideo = null;

  function lookup(w) {
    lastLookup = Date.now();
    var video = document.querySelector('video');
    if (!resumeVideo && video && !video.paused) resumeVideo = video;
    if (video) video.pause();
    document.querySelectorAll('.sw-on').forEach(function (x) { x.classList.remove('sw-on'); });
    w.classList.add('sw-on');

    var line = currentLine();
    var prev = '';
    for (var i = history.length - 1; i >= 0; i--) {
      var h = history[i];
      if (h && h !== line && line.indexOf(h) !== 0 && h.indexOf(line) !== 0) { prev = h; break; }
    }
    var r = w.getBoundingClientRect();
    SubWordBridge.lookup(JSON.stringify({
      word: w.textContent,
      sentence: line,
      previous: prev,
      time: video ? Math.floor(video.currentTime) : 0,
      title: document.title.replace(/ - YouTube$/, ''),
      url: location.href,
      x: r.left, y: r.top, w: r.width, h: r.height,
      vw: window.innerWidth, vh: window.innerHeight
    }));
  }

  // App 收起释义后调用：去掉高亮；点词前在播放的，接着播放
  window.__subwordResume = function () {
    document.querySelectorAll('.sw-on').forEach(function (x) { x.classList.remove('sw-on'); });
    var v = resumeVideo;
    resumeVideo = null;
    if (v) v.play();
  };

  // 长按选中的文字（简介、评论）：选中的文字、所在段落、位置（视口坐标，CSS 像素）
  window.__subwordSelection = function () {
    var sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return null;
    var text = clean(sel.toString());
    if (!text) return null;
    var r = sel.getRangeAt(0);
    var blocks = 'p,li,h1,h2,h3,h4,h5,h6,blockquote,td,dd,dt,pre,div,article,section,span[role=text],yt-formatted-string';
    var el = r.startContainer.nodeType === 1 ? r.startContainer : r.startContainer.parentElement;
    while (el && el !== document.body && !(el.matches(blocks) && clean(el.textContent).length > text.length)) el = el.parentElement;
    var block = el ? clean(el.textContent) : text;
    if (block.length > 1200) block = text;
    var b = r.getBoundingClientRect();
    var video = document.querySelector('video');
    if (!resumeVideo && video && !video.paused) { resumeVideo = video; video.pause(); }
    sel.removeAllRanges();
    return JSON.stringify({
      word: text, sentence: block, previous: '',
      time: video ? Math.floor(video.currentTime) : 0,
      title: document.title.replace(/ - YouTube$/, ''), url: location.href,
      x: b.left, y: b.top, w: b.width, h: b.height,
      vw: window.innerWidth, vh: window.innerHeight
    });
  };
})();
