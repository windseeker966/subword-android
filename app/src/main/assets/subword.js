// 注入 m.youtube.com：画面上字幕里的英文词可以点。点一个词：暂停视频、高亮这个词，
// 把词、整行字幕、前一行字幕和词的位置交给 App（SubWordBridge.lookup）。App 收起释义时调 __subwordResume()。
// YouTube 是单页应用，页面里切换视频不会重新加载，所以只注入一次（重复注入直接返回）。
(function () {
  if (window.__subwordInstalled) return;
  window.__subwordInstalled = true;

  var SEGMENT = '.ytp-caption-segment';

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

  setInterval(function () { remember(currentLine()); }, 200);

  // 手指落在字幕的哪个词上。YouTube 每隔几百毫秒就把字幕元素整个重新生成一遍（全屏时暂停着也会），
  // 所以不改字幕的结构，按下时现算每个词的位置。落在字幕框里但在两个词之间时，取最近的词；
  // 落在字幕框外返回 null
  var WORD = /[A-Za-z0-9][A-Za-z0-9'’\-]*/g;
  var PAD = 8;
  function wordAt(x, y) {
    var segs = document.querySelectorAll(SEGMENT), best = null, bestDist = Infinity;
    for (var i = 0; i < segs.length; i++) {
      var box = segs[i].getBoundingClientRect();
      if (!box.width || x < box.left - PAD || x > box.right + PAD || y < box.top - PAD || y > box.bottom + PAD) continue;
      var walker = document.createTreeWalker(segs[i], NodeFilter.SHOW_TEXT), node;
      while ((node = walker.nextNode())) {
        var m;
        WORD.lastIndex = 0;
        while ((m = WORD.exec(node.data))) {
          var range = document.createRange();
          range.setStart(node, m.index);
          range.setEnd(node, m.index + m[0].length);
          var rects = range.getClientRects();
          for (var k = 0; k < rects.length; k++) {
            var r = rects[k];
            var dx = x < r.left ? r.left - x : x > r.right ? x - r.right : 0;
            var dy = y < r.top ? r.top - y : y > r.bottom ? y - r.bottom : 0;
            var d = dx * dx + dy * dy * 4;   // 上下偏一点比左右偏一点更可能是另一行
            if (d < bestDist) {
              bestDist = d;
              best = { text: m[0], rect: { left: r.left, top: r.top, width: r.width, height: r.height } };
            }
          }
        }
      }
    }
    return best;
  }
  function point(e) {
    var t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]) || e;
    return { x: t.clientX, y: t.clientY };
  }

  // 点到字幕：在 window 的捕获阶段截下整个手势，播放器收不到，不会拖进度条、显示控制条或暂停。
  // 手机版播放器的控制层盖在字幕上面，所以按坐标判断，不看点到的是哪个元素
  var start = null, lastLookup = 0;

  window.addEventListener('touchstart', function (e) {
    start = null;
    if (e.touches.length !== 1) return;
    var p = point(e), w = wordAt(p.x, p.y);
    if (!w) return;
    start = { w: w, x: p.x, y: p.y };
    e.stopImmediatePropagation();
    if (e.cancelable) e.preventDefault();
  }, { capture: true, passive: false });

  ['touchmove', 'touchend', 'touchcancel'].forEach(function (type) {
    window.addEventListener(type, function (e) {
      if (!start) return;
      e.stopImmediatePropagation();
      if (e.cancelable) e.preventDefault();   // 不再生成 click，播放器不会再收到一次
      if (type !== 'touchend') { if (type === 'touchcancel') start = null; return; }
      var p = point(e), s = start;
      start = null;
      if (Math.abs(p.x - s.x) < 16 && Math.abs(p.y - s.y) < 16) lookup(s.w);
    }, { capture: true, passive: false });
  });

  ['pointerdown', 'pointermove', 'pointerup', 'mousedown', 'mouseup', 'click', 'dblclick'].forEach(function (type) {
    window.addEventListener(type, function (e) {
      // 触摸时 touchstart 已经认出了词（pointerdown 比 touchstart 先到，也按坐标判断）；
      // 鼠标或触控笔没有 touch 事件，在 click 时查词
      var w = start ? start.w : wordAt(e.clientX, e.clientY);
      if (!w) return;
      e.stopImmediatePropagation();
      if (e.cancelable && type !== 'pointerdown' && type !== 'pointermove') e.preventDefault();
      if (type === 'click' && !start && Date.now() - lastLookup > 600) lookup(w);
    }, { capture: true, passive: false });
  });

  // 高亮：叠在词上的半透明黄框，字幕元素被重新生成也不会丢。全屏时要放进全屏的元素里才看得到
  var mark = null;
  function highlight(r) {
    unhighlight();
    mark = document.createElement('div');
    mark.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border-radius:4px;' +
      'background:rgba(255,213,79,0.45);box-shadow:0 0 0 2px #FFD54F;' +
      'left:' + (r.left - 2) + 'px;top:' + (r.top - 1) + 'px;width:' + (r.width + 4) + 'px;height:' + (r.height + 2) + 'px';
    (document.fullscreenElement || document.body).appendChild(mark);
  }
  function unhighlight() {
    if (mark && mark.parentNode) mark.parentNode.removeChild(mark);
    mark = null;
  }

  var resumeVideo = null;

  function lookup(w) {
    lastLookup = Date.now();
    var video = document.querySelector('video');
    if (!resumeVideo && video && !video.paused) resumeVideo = video;
    if (video) video.pause();
    highlight(w.rect);

    var line = currentLine();
    var prev = '';
    for (var i = history.length - 1; i >= 0; i--) {
      var h = history[i];
      if (h && h !== line && line.indexOf(h) !== 0 && h.indexOf(line) !== 0) { prev = h; break; }
    }
    var r = w.rect;
    SubWordBridge.lookup(JSON.stringify({
      word: w.text,
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
    unhighlight();
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
