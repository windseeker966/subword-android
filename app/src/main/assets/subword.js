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

  // 字幕所在的框，最近一次看到的。YouTube 重新生成字幕元素的那一下，按下去时可能正好一个词都找不到；
  // 自动字幕是滚动的，来新的一行时整块字幕会挪几个像素，按在字幕边上也可能落空。
  // 落在刚才的字幕框或现在的字幕窗口附近（放宽 NEAR）就先截下来，抬起手指时在放宽的范围里找最近的词
  var capBox = null;
  function rememberBox() {
    var segs = document.querySelectorAll(SEGMENT), b = null;
    for (var i = 0; i < segs.length; i++) {
      var r = segs[i].getBoundingClientRect();
      if (!r.width) continue;
      b = b ? { left: Math.min(b.left, r.left), top: Math.min(b.top, r.top), right: Math.max(b.right, r.right), bottom: Math.max(b.bottom, r.bottom) }
            : { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
    }
    if (b) { b.t = Date.now(); capBox = b; }
  }
  var NEAR = 16;
  function inBox(b, x, y, pad) {
    return x >= b.left - pad && x <= b.right + pad && y >= b.top - pad && y <= b.bottom + pad;
  }
  function nearCaption(x, y) {
    if (capBox && Date.now() - capBox.t < 1000 && inBox(capBox, x, y, NEAR)) return true;
    var wins = document.querySelectorAll('.caption-window');
    for (var i = 0; i < wins.length; i++) {
      var r = wins[i].getBoundingClientRect();
      if (r.width && inBox(r, x, y, NEAR)) return true;
    }
    return false;
  }

  setInterval(function () { remember(currentLine()); rememberBox(); }, 200);

  var PAD = 8;

  // 手指落在字幕的哪个词上。YouTube 每隔几百毫秒就把字幕元素整个重新生成一遍（全屏时暂停着也会），
  // 所以不改字幕的结构，按下时现算每个词的位置。落在字幕框里但在两个词之间时，取最近的词；
  // 落在字幕框外返回 null
  var WORD = /[A-Za-z0-9][A-Za-z0-9'’\-]*/g;
  function wordAt(x, y, pad) {
    if (pad === undefined) pad = PAD;
    var segs = document.querySelectorAll(SEGMENT), best = null, bestDist = Infinity;
    for (var i = 0; i < segs.length; i++) {
      var box = segs[i].getBoundingClientRect();
      if (!box.width || !inBox(box, x, y, pad)) continue;
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

  // 全屏时控制条没出来：单指点画面不传给播放器（不会点出进度条），两指同时点一下才调出控制条。
  // 控制条出来以后照常点（暂停、拖进度条、退出全屏）。手机版播放器显示控制条时控制层带 fadein；
  // 认不出来时，两指调出后的几秒内也放行
  var passUntil = 0;
  function overlayShown() {
    var o = document.querySelector('#player-control-overlay');
    return !!(o && o.classList.contains('fadein'));
  }
  function controlsShown() {
    return Date.now() < passUntil || overlayShown();
  }
  function blockTaps() {
    return !!document.fullscreenElement && !controlsShown();
  }

  // 控制条亮着时按在按钮、进度条、时间上：小屏时字幕正好压在全屏按钮和进度条上，这时让给控制条
  var CONTROL = 'button, [role=button], [role=slider], yt-progress-bar, player-time-display';
  function onControl(x, y) {
    if (!overlayShown()) return false;
    var els = document.elementsFromPoint(x, y);
    for (var i = 0; i < els.length; i++) {
      var c = els[i].closest(CONTROL);
      if (c && c.closest('#player-control-overlay')) return true;
    }
    return false;
  }

  // 这一下按下要不要截下来：落在字幕的词上（w 是那个词）；落在刚才字幕的位置上，但字幕正在重新生成
  // （cap，抬起时再找词）；或者全屏时控制条没出来。控制条亮着时按在控制上的不截
  function decide(x, y) {
    if (onControl(x, y)) return null;
    var w = wordAt(x, y);
    if (w) return { w: w, x: x, y: y };
    if (nearCaption(x, y)) return { w: null, cap: true, x: x, y: y };
    if (blockTaps()) return { w: null, x: x, y: y };
    return null;
  }

  // 照真的点一下的顺序把事件发给 el，播放器当成在 (x, y) 点了一下：控制条没出来时调出，出来了就收起
  var passing = false;
  function tapPlayer(el, x, y) {
    var opt = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, pointerId: 1, pointerType: 'touch', isPrimary: true };
    function touch(type) {
      var t = new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
      return new TouchEvent(type, { bubbles: true, cancelable: true, view: window,
        touches: type === 'touchstart' ? [t] : [], targetTouches: type === 'touchstart' ? [t] : [], changedTouches: [t] });
    }
    passing = true;
    try {
      el.dispatchEvent(new PointerEvent('pointerdown', opt));
      el.dispatchEvent(touch('touchstart'));
      el.dispatchEvent(new PointerEvent('pointerup', opt));
      el.dispatchEvent(touch('touchend'));
      el.dispatchEvent(new MouseEvent('mousedown', opt));
      el.dispatchEvent(new MouseEvent('mouseup', opt));
      el.dispatchEvent(new MouseEvent('click', opt));
    } finally {
      passing = false;
    }
  }

  // 两指点一下：点两指中间，调出控制条。这个视频还没点过播放器时控制层还没生成，第一下只生成控制层，
  // 控制条不出来，再点一下
  function showControls(x, y) {
    passUntil = Date.now() + 4000;
    var built = !!document.querySelector('#player-control-overlay');
    var el = document.elementFromPoint(x, y);
    if (el) tapPlayer(el, x, y);
    if (built) return;
    setTimeout(function () {
      if (overlayShown()) return;
      var again = document.elementFromPoint(x, y);
      if (again) tapPlayer(again, x, y);
    }, 300);
  }

  // 收起释义、接着播放时，播放器一开始播放就把控制条亮出来。全屏时先把控制层整个设成透明，看不到闪一下；
  // 刚亮出来的头几十毫秒播放器不理点击，等 400ms 再点一下空白处真的收起，收起后去掉透明
  function hideControlsSoon() {
    var veil = document.createElement('style');
    // 播放器给控制条的淡入用了过渡动画，过渡比 !important 还优先，一起关掉
    veil.textContent = '#player-control-overlay, #player-control-overlay * { opacity: 0 !important; transition: none !important; animation: none !important; }';
    (document.head || document.documentElement).appendChild(veil);
    var start = Date.now(), tapped = false;
    var timer = setInterval(function () {
      var age = Date.now() - start, shown = overlayShown();
      if ((!shown && (tapped || age > 1500)) || age > 3000) {
        clearInterval(timer);
        veil.remove();
        return;
      }
      if (!shown || tapped || age < 400) return;
      tapped = true;
      var bg = document.querySelector('#player-control-overlay .player-controls-background');
      if (!bg) return;
      var r = bg.getBoundingClientRect();
      // 左上的空白处，避开中间的播放键和底下的进度条
      tapPlayer(bg, r.left + r.width * 0.25, r.top + r.height * 0.3);
    }, 100);
  }

  // 截下的手势：在 window 的捕获阶段截下整个手势，播放器收不到，不会拖进度条、显示控制条或暂停。
  // 手机版播放器的控制层盖在字幕上面，所以按坐标判断，不看点到的是哪个元素
  var g = null, lastLookup = 0;
  function swallow(e) {
    e.stopImmediatePropagation();
    if (e.cancelable) e.preventDefault();   // 不再生成 click，播放器不会再收到一次
  }

  window.addEventListener('touchstart', function (e) {
    if (passing) return;
    if (e.touches.length === 1) {
      var p = point(e);
      g = decide(p.x, p.y);
      if (g) g.t = Date.now();
    } else if (g && !g.two) {
      // 第二个手指跟着按下来：两指点
      var a = e.touches[0], b = e.touches[1];
      g.two = { x: (a.clientX + b.clientX) / 2, y: (a.clientY + b.clientY) / 2 };
      g.t = Date.now();
    }
    if (g) swallow(e);
  }, { capture: true, passive: false });

  window.addEventListener('touchmove', function (e) {
    if (passing || !g) return;
    swallow(e);
    // 一指：离按下的地方超过 16px 算拖动；两指：两指中点挪开超过 24px（捏合、滑动）不算点
    if (g.two) {
      if (e.touches.length < 2) return;
      var a = e.touches[0], b = e.touches[1];
      var mx = (a.clientX + b.clientX) / 2, my = (a.clientY + b.clientY) / 2;
      if (Math.abs(mx - g.two.x) >= 24 || Math.abs(my - g.two.y) >= 24) g.moved = true;
    } else {
      var p = point(e);
      if (Math.abs(p.x - g.x) >= 16 || Math.abs(p.y - g.y) >= 16) g.moved = true;
    }
  }, { capture: true, passive: false });

  window.addEventListener('touchend', function (e) {
    if (passing || !g) return;
    swallow(e);
    if (e.touches.length > 0) return;   // 等手指都抬起来
    var s = g;
    g = null;
    if (s.two) {
      if (!s.moved && Date.now() - s.t < 500) showControls(s.two.x, s.two.y);
    } else if (!s.moved) {
      var w = s.w || (s.cap && wordAt(s.x, s.y, NEAR));
      if (w) lookup(w);
    }
  }, { capture: true, passive: false });

  window.addEventListener('touchcancel', function (e) {
    if (passing || !g) return;
    swallow(e);
    if (e.touches.length === 0) g = null;
  }, { capture: true, passive: false });

  ['pointerdown', 'pointermove', 'pointerup', 'mousedown', 'mouseup', 'click', 'dblclick'].forEach(function (type) {
    window.addEventListener(type, function (e) {
      if (passing) return;
      // 触摸时 pointerdown 比 touchstart 先到，也按坐标判断；
      // 鼠标或触控笔没有 touch 事件，在 click 时查词
      var hit = g || decide(e.clientX, e.clientY);
      if (!hit) return;
      e.stopImmediatePropagation();
      if (e.cancelable && type !== 'pointerdown' && type !== 'pointermove') e.preventDefault();
      if (type === 'click' && !g && hit.w && Date.now() - lastLookup > 600) lookup(hit.w);
    }, { capture: true, passive: false });
  });

  // 释义开着时 App 盖了一层透明层，点在上面先问这里：落在字幕的词上就查这个词（视频保持暂停），
  // 返回 true；不在词上返回 false，App 收起释义。坐标是占网页宽高的比例
  window.__subwordTap = function (fx, fy) {
    var w = wordAt(fx * window.innerWidth, fy * window.innerHeight);
    if (!w) return false;
    lookup(w);
    return true;
  };

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
  /** 点词前（释义还没开着时）控制条是不是开着：开着就不替用户收起 */
  var looking = false, hadControls = false;

  function lookup(w) {
    lastLookup = Date.now();
    if (!looking) { looking = true; hadControls = overlayShown(); }
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
    looking = false;
    var v = resumeVideo;
    resumeVideo = null;
    if (!v) return;
    v.play();
    if (document.fullscreenElement && !hadControls) hideControlsSoon();
  };

  // 评论：整条评论包在一个按钮里（预览卡片也是），点一下、长按时整条盖上一层半透明蓝色，像是整条被选中了。
  // 去掉这层点按高亮，长按照常只选中手指下的词
  var css = document.createElement('style');
  css.textContent = 'ytm-comment-thread-renderer, ytm-comment-thread-renderer *, yt-video-metadata-carousel-view-model,' +
    'yt-video-metadata-carousel-view-model * { -webkit-tap-highlight-color: transparent !important; }';
  (document.head || document.documentElement).appendChild(css);

  // 长按选中的文字（简介、评论）：选中的文字、所在段落、位置（视口坐标，CSS 像素）
  function selectionNow() {
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
    return { text: text, block: block, rect: { left: b.left, top: b.top, width: b.width, height: b.height }, t: Date.now() };
  }
  // 最近一次选中的内容：点菜单时选区可能已经被清掉了（评论列表重新渲染、关菜单），用刚才记下的
  var lastSelection = null;
  document.addEventListener('selectionchange', function () {
    var s = selectionNow();
    if (s) lastSelection = s;
  });

  window.__subwordSelection = function () {
    var s = selectionNow();
    if (!s && lastSelection && Date.now() - lastSelection.t < 30000) s = lastSelection;
    lastSelection = null;
    if (!s) return null;
    var video = document.querySelector('video');
    if (!resumeVideo && video && !video.paused) { resumeVideo = video; video.pause(); }
    var sel = window.getSelection();
    if (sel) sel.removeAllRanges();
    var b = s.rect;
    return JSON.stringify({
      word: s.text, sentence: s.block, previous: '',
      time: video ? Math.floor(video.currentTime) : 0,
      title: document.title.replace(/ - YouTube$/, ''), url: location.href,
      x: b.left, y: b.top, w: b.width, h: b.height,
      vw: window.innerWidth, vh: window.innerHeight
    });
  };
})();
