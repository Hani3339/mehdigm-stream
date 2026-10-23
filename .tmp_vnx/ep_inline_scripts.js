
(function(){
  'use strict';
  var STORAGE_KEY='wa_continue_watching_v1';
  var MAX_ITEMS=12;
  var item={"id":"152166","title":"انمي Tokyo Revengers: Santen Sensou-hen الحلقة 4 مترجمة","anime":"Tokyo Revengers: Santen Sensou-hen","episodeNumber":"4","url":"https://w1.anime4up.rest/episode/%d8%a7%d9%86%d9%85%d9%8a-tokyo-revengers-santen-sensou-hen-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-4-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/","poster":"https://w1.anime4up.rest/wp-content/uploads/2026/10/bx178083-bg7pg6TCHwtG.jpg"};
  var saved=false;
  function save(){
    if(saved||!item||!item.id||!item.url)return;
    saved=true;
    try{
      var raw=localStorage.getItem(STORAGE_KEY),list=raw?JSON.parse(raw):[];
      if(!Array.isArray(list))list=[];
      list=list.filter(function(x){return x&&String(x.id)!==String(item.id)});
      item.updatedAt=Date.now();
      list.unshift(item);
      localStorage.setItem(STORAGE_KEY,JSON.stringify(list.slice(0,MAX_ITEMS)));
    }catch(e){}
  }
  document.addEventListener('DOMContentLoaded',function(){
    /* فتح الحلقة وحده لا يُحسب فورًا؛ نسجلها بعد بقاء المستخدم قليلًا أو اختيار سيرفر. */
    window.setTimeout(save,8000);
    var servers=document.getElementById('episode-servers');
    if(servers)servers.addEventListener('click',function(e){if(e.target.closest('li[data-watch]'))save()},true);
    var player=document.getElementById('episode-player');
    if(player)player.addEventListener('click',save,{once:true,capture:true});
  });
})();


// --- SCRIPT ---


(function () {
function initWatch() {
    const serversList = document.getElementById("episode-servers");
    const wrapperBox  = document.querySelector("#episode-player .videoWrapper");
    const playerBox   = document.querySelector("#episode-player .wa-real-player");
    const watchTab    = document.getElementById("watch-tab-1");
    const watchSplash = document.getElementById("watch-splash");
    const serverArea = serversList ? serversList.closest(".wa-watch-server-area") : null;

    if (!serversList || !wrapperBox || !playerBox) return;
    if (serversList.getAttribute("data-wa-initialized") === "1") return;
    serversList.setAttribute("data-wa-initialized", "1");

    const statusBox = document.getElementById("wa-watch-status");
    const statusText = document.getElementById("wa-watch-status-text");
    const playerStatus = document.getElementById("wa-player-status");
    const playerStatusText = document.getElementById("wa-player-status-text");
    const retryButton = document.getElementById("wa-watch-retry");
    let slowTimer = null;

    function setStatus(state, message) {
        if (!statusBox) return;
        statusBox.hidden = state === "ready";
        statusBox.setAttribute("data-state", state);
        if (statusText && message) statusText.textContent = message;
    }

    function setPlayerStatus(state, message) {
        if (!playerStatus) return;
        playerStatus.hidden = state === "ready";
        playerStatus.setAttribute("data-state", state);
        if (playerStatusText && message) playerStatusText.textContent = message;
        if (retryButton) retryButton.hidden = state !== "slow";
    }

    function revealPlayer() {
        if (serverArea) serverArea.classList.add("wa-servers-open");
        if (watchTab) {
            watchTab.classList.add("wa-player-started");
            watchTab.style.display = "block";
        }
        if (watchSplash) {
            watchSplash.classList.add("wa-splash-dismissed");
            watchSplash.setAttribute("aria-hidden", "true");
            watchSplash.setAttribute("tabindex", "-1");
        }
    }

    let playerStarted = false;
    let internalPlayerWrite = false;
    let currentServerUrl = "";
    let currentIframe = null;

    function normalizeUrl(url) {
        try {
            const value = String(url || "").trim();
            if (!value) return "";
            const parsed = new URL(value, window.location.href);
            return /^https?:$/.test(parsed.protocol) ? parsed.href : "";
        } catch (e) {
            return "";
        }
    }

    function markActive(li) {
        serversList.querySelectorAll("li").forEach(function (el) {
            el.classList.remove("active", "ISActive");
            const a = el.querySelector("a");
            if (a) a.setAttribute("aria-pressed", "false");
        });
        li.classList.add("active", "ISActive");
        const a = li.querySelector("a");
        if (a) a.setAttribute("aria-pressed", "true");
    }

    /*
     * عزل حاوية المشغل عن أكواد القالب والإعلانات:
     * بعض الأكواد تستعمل .html() أو innerHTML بعد مهلة، فتزيل iframe وتعيد إنشاءه.
     * نسمح بالكتابة داخل .wa-real-player فقط من هذا السكربت.
     */
    const innerHTMLDescriptor = Object.getOwnPropertyDescriptor(Element.prototype, "innerHTML");
    const textContentDescriptor = Object.getOwnPropertyDescriptor(Node.prototype, "textContent");
    // replaceChildren is absent in some older mobile browsers / in-app WebViews.
    const nativeReplaceChildren = function (node) {
        while (playerBox.firstChild) Node.prototype.removeChild.call(playerBox, playerBox.firstChild);
        if (node) Node.prototype.appendChild.call(playerBox, node);
        // Keep the loader inside the episode player; the video ad lives in the outer wrapper.
        if (playerStatus) Node.prototype.appendChild.call(playerBox, playerStatus);
    };
    const nativeAppendChild = playerBox.appendChild.bind(playerBox);
    const nativeInsertBefore = playerBox.insertBefore.bind(playerBox);
    const nativeRemoveChild = playerBox.removeChild.bind(playerBox);
    const nativeReplaceChild = playerBox.replaceChild.bind(playerBox);

    try {
        if (innerHTMLDescriptor && innerHTMLDescriptor.configurable !== false) {
            Object.defineProperty(playerBox, "innerHTML", {
                configurable: true,
                get: function () {
                    return innerHTMLDescriptor.get.call(this);
                },
                set: function (value) {
                    if (internalPlayerWrite) {
                        innerHTMLDescriptor.set.call(this, value);
                    }
                }
            });
        }

        if (textContentDescriptor && textContentDescriptor.configurable !== false) {
            Object.defineProperty(playerBox, "textContent", {
                configurable: true,
                get: function () {
                    return textContentDescriptor.get.call(this);
                },
                set: function (value) {
                    if (internalPlayerWrite) {
                        textContentDescriptor.set.call(this, value);
                    }
                }
            });
        }
    } catch (e) {
        /* بعض المتصفحات قد تمنع تعريف الخاصية؛ الحماية الأخرى تبقى فعالة. */
    }

    playerBox.replaceChildren = function () {
        if (!internalPlayerWrite) return;
        return nativeReplaceChildren.apply(playerBox, arguments);
    };

    playerBox.appendChild = function (node) {
        if (!internalPlayerWrite) return node;
        return nativeAppendChild(node);
    };

    playerBox.insertBefore = function (node, referenceNode) {
        if (!internalPlayerWrite) return node;
        return nativeInsertBefore(node, referenceNode);
    };

    playerBox.removeChild = function (node) {
        if (!internalPlayerWrite) return node;
        return nativeRemoveChild(node);
    };

    playerBox.replaceChild = function (newNode, oldNode) {
        if (!internalPlayerWrite) return oldNode;
        return nativeReplaceChild(newNode, oldNode);
    };

    function renderServer(li, forceReload) {
        if (!li) return;

        const rawUrl = li.getAttribute("data-watch") || "";
        const requestedUrl = normalizeUrl(rawUrl);
        if (!requestedUrl) return;

        markActive(li);
        revealPlayer();

        /* اختيار السيرفر النشط نفسه لا يعيد تحميل الفيديو. */
        if (
            currentIframe &&
            currentIframe.isConnected &&
            currentServerUrl === requestedUrl && !forceReload
        ) {
            return;
        }

        const iframe = document.createElement("iframe");
        clearTimeout(slowTimer);
        setPlayerStatus("loading", "جارٍ تحميل سيرفر المشاهدة…");
        // A cross-origin iframe load only confirms its document, not video playback.
        iframe.addEventListener("load", function () {
            if (currentIframe !== iframe) return;
            clearTimeout(slowTimer);
            setPlayerStatus("ready");
        });
        iframe.addEventListener("error", function () {
            if (currentIframe !== iframe) return;
            clearTimeout(slowTimer);
            setPlayerStatus("slow", "تعذّر تحميل المشغل. جرّب سيرفرًا آخر أو أعد المحاولة.");
        });
        iframe.src = requestedUrl;
        iframe.title = "مشغل الحلقة";
        iframe.allowFullscreen = true;
        iframe.setAttribute("allowfullscreen", "");
        iframe.setAttribute("referrerpolicy", "no-referrer-when-downgrade");

        currentIframe = iframe;
        currentServerUrl = requestedUrl;
        playerStarted = true;
        slowTimer = window.setTimeout(function () {
            if (currentIframe === iframe) setPlayerStatus("slow", "السيرفر يستغرق وقتًا في التحميل. يمكنك الانتظار أو اختيار سيرفر آخر أو إعادة المحاولة.");
        }, 15000);
        internalPlayerWrite = true;
        try {
            nativeReplaceChildren(iframe);
        } finally {
            internalPlayerWrite = false;
        }

    }

    function activateMainServerOnce() {
        if (serverArea) serverArea.classList.add("wa-servers-open");
        if (playerStarted) return;

        const mainServer =
            serversList.querySelector("li.ISActive[data-watch]") ||
            serversList.querySelector("li.active[data-watch]") ||
            serversList.querySelector("li[data-watch]");

        if (mainServer) renderServer(mainServer);
    }

    /*
     * نستقبل ضغط السيرفر في مرحلة capture ونوقفه هنا.
     * بهذا لا يصل الضغط نفسه إلى معالج آخر في القالب أو الإعلان ليعيد تحميل iframe لاحقًا.
     */
    serversList.addEventListener("click", function (e) {
        const li = e.target.closest("li[data-watch]");
        if (!li || !serversList.contains(li)) return;

        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        renderServer(li);
    }, true);

    serversList.addEventListener("keydown", function (e) {
        if (e.key !== "Enter" && e.key !== " ") return;
        const li = e.target.closest("li[data-watch]");
        if (!li) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        renderServer(li);
    }, true);

    if (retryButton) retryButton.addEventListener("click", function (e) {
        e.preventDefault();
        e.stopPropagation();
        const active = serversList.querySelector("li.ISActive[data-watch]") || serversList.querySelector("li[data-watch]");
        renderServer(active, true);
    });

	
	
	

	
	// ترتيب megamax 2: غيّر الرقم لتحديد موضعه في القائمة.
// تستخدمه بقية وظائف الصفحة.
const firstServer = serversList.querySelector("li[data-watch]");

// البحث عن رابط MegaMax الأصلي مهما كان ترتيبه.
const megamaxSource = Array.from(
    serversList.querySelectorAll("li[data-watch]")
).find(function (li) {
    const url = li.getAttribute("data-watch") || "";
    return /share4max\.com\/iframe\/([A-Za-z0-9_-]+)/.test(url);
});

if (megamaxSource) {
    const sourceUrl = megamaxSource.getAttribute("data-watch") || "";
    const match = sourceUrl.match(
        /share4max\.com\/iframe\/([A-Za-z0-9_-]+)/
    );

    if (match) {
        const newServerUrl = "https://share4max.com/e/" + match[1];

        // استخدام الزر الموجود لمنع التكرار.
        let megamax2 = Array.from(
            serversList.querySelectorAll("li[data-watch]")
        ).find(function (li) {
            return normalizeUrl(li.getAttribute("data-watch")) ===
                normalizeUrl(newServerUrl);
        });

        if (!megamax2) {
            megamax2 = document.createElement("li");
            megamax2.setAttribute("data-watch", newServerUrl);

            megamax2.innerHTML =
                '<a role="button" tabindex="0" ' +
                'aria-label="تشغيل سيرفر megamax 2" aria-pressed="false">' +
                '<span class="watch-server-label" dir="ltr">' +
                '<bdi class="watch-server-name">megamax 2</bdi>' +
                '</span>' +
                '<span class="quality" dir="ltr">متعدد الجودات</span>' +
                '</a>';
        }

        // تحديد آخر سيرفر من anime4up1 وanime4up2 في القائمة.
        let insertAfter = null;

        serversList.querySelectorAll("li[data-watch]").forEach(function (li) {
            if (li === megamax2) return;

            const nameElement = li.querySelector(".watch-server-name");
            const name = nameElement
                ? nameElement.textContent.trim().toLowerCase()
                    .replace(/[\s_-]+/g, "")
                : "";

            if (name === "anime4up1" || name === "anime4up2") {
                insertAfter = li;
            }
        });

        if (insertAfter) {
            insertAfter.insertAdjacentElement("afterend", megamax2);
        } else {
            // إذا غاب السيرفران، يظهر بعد MegaMax الأصلي.
            megamaxSource.insertAdjacentElement("afterend", megamax2);
        }
    }
}
	
	
	
    // The server list stays hidden until the viewer presses the splash image.
    setStatus(firstServer ? "ready" : "empty", "لا توجد سيرفرات مشاهدة مضافة لهذه الحلقة حاليًا.");
    if (watchSplash) {
        watchSplash.addEventListener("click", function (e) {
            if (e.target.closest("#video-ad-container")) return;
            // Let the existing video-ad click handlers run before opening the episode.
            window.requestAnimationFrame(function () {
                window.setTimeout(activateMainServerOnce, 0);
            });
        }, true);
        watchSplash.addEventListener("keydown", function (e) {
            if (e.key !== "Enter" && e.key !== " ") return;
            e.preventDefault();
            window.setTimeout(activateMainServerOnce, 0);
        }, true);
    }
    if (!watchSplash || getComputedStyle(watchSplash).display === "none") activateMainServerOnce();

    // Restore removed buttons without touching an already playing iframe.
    if (window.MutationObserver && firstServer) {
        const savedButtons = Array.from(serversList.children).map(function (li) { return li.cloneNode(true); });
        const listObserver = new MutationObserver(function () {
            const actualUrls = Array.from(serversList.children).map(function (li) { return li.getAttribute("data-watch"); });
            const missing = savedButtons.filter(function (li) {
                return actualUrls.indexOf(li.getAttribute("data-watch")) === -1;
            });
            if (!missing.length) return;
            listObserver.disconnect();
            missing.forEach(function (li) { serversList.appendChild(li.cloneNode(true)); });
            if (currentServerUrl) {
                const active = Array.from(serversList.children).find(function (li) {
                    return normalizeUrl(li.getAttribute("data-watch")) === currentServerUrl;
                });
                if (active) markActive(active);
            }
            listObserver.observe(serversList, {childList: true});
        });
        listObserver.observe(serversList, {childList: true});
    }

    window.addEventListener("pageshow", function () {
        if (!playerStarted) setStatus(firstServer ? "ready" : "empty");
    });
}
// Works both during initial parsing and when a cache/optimization plugin runs it late.
if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initWatch, {once: true});
} else {
    initWatch();
}
})();


// --- SCRIPT ---


if (window.jQuery) {
(function(w,d,$){ "use strict";
  /* ===== Config ===== */
  w.AjaxtURL = "https://w1.anime4up.rest/wp-content/themes/Anime4up-SEO-Continue-Watching-Fixed/Ajaxt/";
  w.LoaderVar = $('<div class="loader"><div class="sk-chase"><div class="sk-chase-dot"></div><div class="sk-chase-dot"></div><div class="sk-chase-dot"></div><div class="sk-chase-dot"></div><div class="sk-chase-dot"></div><div class="sk-chase-dot"></div></div></div>');

  /* ===== Main widgets (owl) ===== */
  $(function(){
    $('.main-widget').each(function(){
      const $w = $(this),
            $outer = $w.find('.Slider--Outer'),
            $slider = $w.find('.anime-slider'),
            $prev = $w.find('.slider-prev'),
            $next = $w.find('.slider-next');

      if (!$slider.length) return;

      $slider.owlCarousel({
        rtl: true,
        loop: false,
        margin: 12,
        mouseDrag: true,
        touchDrag: true,
        pullDrag: true,
        smartSpeed: 350,
        dots: false,
        nav: false,
        responsive: {
          '0': { items: 2 },
          '576': { items: 2 },
          '992': { items: 3 },
          '1030': { items: 5 }
        }
      });

      const update = function(){
        const api = $slider.data('owl.carousel');
        if (!api) return;

        const curr = api.relative(api.current()),
              vis = api.settings.items,
              total = api.$stage.children('.owl-item').not('.cloned').length,
              max = Math.max(0, total - vis);

        $prev.prop('disabled', curr <= 0 || total <= vis);
        $next.prop('disabled', curr >= max || total <= vis);
      };

      $prev.on('click', function(e){
        e.preventDefault();
        $slider.trigger('prev.owl.carousel');
      });

      $next.on('click', function(e){
        e.preventDefault();
        $slider.trigger('next.owl.carousel');
      });

      $slider.on('initialized.owl.carousel changed.owl.carousel resized.owl.carousel refreshed.owl.carousel', update);

      setTimeout(function(){
        $outer.css({height:'auto'});
        update();
      }, 500);
    });
  });

  /* ===== Search head (instant) ===== */
  $(function(){
    const $b=$('body'), $h=$('.SearchHead'), $f=$h.find('form'), $i=$f.find('input[name="s"],input[type="text"]').first(),
          $r=$('.SearchListResult'), $in=$('.SearchInnerList'), $orig=$f, $t=$('.search--userarea--rightbar');
    const close=()=>{$h.removeClass('SearchFixed');$r.removeClass('show');$b.removeClass('SearchActive');$i.val('')};
    $i.on('input keyup',function(){
      const v=$.trim($i.val()); $h.addClass('SearchFixed'); $r.addClass('show'); $b.addClass('SearchActive'); $in.find('.SearchListMoreButton').remove();
      if(v){ $in.html(w.LoaderVar);
        $.post(w.AjaxtURL+'Searching.php',{search:v},m=>{$in.show().html(m).append(
          '<a class="SearchListMoreButton" href="https://w1.anime4up.rest/?s='+encodeURIComponent(v)+'">عرض المزيد</a>');},'html');
      }else{ $in.empty().show(); }
    });
    $(d).on('mouseup',e=>{if(!$h.is(e.target)&&!$h.has(e.target).length)$h.removeClass('Active')});
    $('header.header .SearchHead input').on('focus',()=>{$h.addClass('Active');$r.addClass('show')});
    $b.on('click','.search--toggle',()=>{$b.toggleClass('searchOn')});
    $('header.Main--Header .Header--Search input').on('focus',()=>$('.Header--Search').addClass('Active'));
    $(d).on('click','.SearchInnerList',e=>e.stopPropagation());
    $(d).on('click','.SearchListResult.show',e=>{ if($(e.target).closest('.SearchInnerList,.search--userarea--rightbar').length)return; close(); });
    $(d).on('click','.Close--SearchingBox',e=>{e.preventDefault();close()});
    const _open=d.getElementById('search_open'), _hide=d.getElementById('hideSearch');
    if(_open&&_hide){ const t=()=>{ const head=d.querySelector('.SearchHead'); head&&head.classList.toggle('open') }; _open.onclick=_hide.onclick=t; }
    const node=$h.get(0);
    if(node){ new MutationObserver(ms=>{ms.forEach(m=>{
      if($(m.target).hasClass('SearchFixed')){ if($i.length && !$t.find('input[name="s"]').length){ $i.detach().prependTo($t).focus(); } }
      else{ if($i.length && !$orig.find('input[name="s"]').length){ $i.detach().prependTo($orig); } }
    })}).observe(node,{attributes:true,attributeFilter:['class']});}
  });

  /* ===== Lazy images / backgrounds ===== */
  (function(){
    const loadImg=el=>{const s=el.dataset.image; if(!s) return; el.src=s; el.onload=()=>{el.classList.add('Loaded'); el.removeAttribute('data-image')}; el.onerror=()=>el.remove();};
    const loadBg=el=>{const s=el.dataset.src||el.dataset.bg; if(!s) return; el.style.backgroundImage=`url("${s}")`; el.classList.add('bgLoaded'); el.removeAttribute('data-src'); el.removeAttribute('data-bg');};
    let io=null;
    const onIn=ents=>ents.forEach(e=>{ if(!e.isIntersecting) return; const el=e.target; (el.tagName==='IMG'?loadImg:loadBg)(el); io&&io.unobserve(el); });
    const boot=root=>root.querySelectorAll('img.imgInit[data-image]:not([data-lzy]),.image[data-src]:not([data-lzy]),[data-bg]:not([data-lzy])')
      .forEach(el=>{el.dataset.lzy=1; io?io.observe(el):(el.tagName==='IMG'?loadImg(el):loadBg(el))});
    const init=()=>{ if('IntersectionObserver'in w) io=new IntersectionObserver(onIn,{rootMargin:'200px 0px',threshold:.1}); boot(d);
      new MutationObserver(ms=>ms.forEach(m=>{m.addedNodes&&m.addedNodes.forEach(n=>{ if(n.nodeType===1) boot(n) })})).observe(d.body,{childList:true,subtree:true});
    };
    d.readyState==='loading'?d.addEventListener('DOMContentLoaded',init):init();
  })();

  /* ===== Lucodeia slider - Hero slider sub-pixel fix ===== */
  $(function(){
    const $p   = $('.Slider--pind');
    const $owl = $('.owl-carousel.lucodeia-slider');

    if (!$owl.length) return;

    /*
     * سبب ظهور جزء من الصورة التالية عندك كان فرق 1px تقريبًا:
     * outer width = 1910
     * active item width = 1909
     * لذلك نثبت عرض عناصر Owl يدويًا على عرض الإطار + 2px overlap بسيط.
     */

    const injectHeroSliderCss = function(){
      if (d.getElementById('wa-hero-slider-subpixel-css')) return;

      const css = [
        '.Slider--pind{width:100%;max-width:100%;overflow:hidden!important;position:relative;}',
        '.Slider--pind .owl-stage-outer{width:100%;max-width:100%;overflow:hidden!important;}',
        '.Slider--pind .owl-stage{margin:0!important;padding:0!important;}',
        '.Slider--pind .owl-item{margin:0!important;padding:0!important;overflow:hidden!important;}',
        '.Slider--pind .owl-item.active{z-index:5!important;}',
        '.Slider--pind .owl-item:not(.active){z-index:1!important;}',
        '.Slider--pind .item{margin:0!important;padding:0!important;overflow:hidden!important;}',
        '.Slider--pind .lucodeia-slider-slide-item{display:block!important;width:calc(100% + 6px)!important;max-width:none!important;margin-left:-3px!important;margin-right:-3px!important;border-radius:0!important;overflow:hidden!important;background-size:cover!important;background-position:center center!important;background-repeat:no-repeat!important;}',
        '.Slider--pind .lucodeia-slider-slide-item::after{inset:0!important;width:100%!important;max-width:100%!important;}'
      ].join('');

      const style = d.createElement('style');
      style.id = 'wa-hero-slider-subpixel-css';
      style.type = 'text/css';
      style.appendChild(d.createTextNode(css));
      d.head.appendChild(style);
    };

    const hardFixHeroWidths = function(){
      injectHeroSliderCss();

      const outer = $p.find('.owl-stage-outer').get(0);
      const stage = $p.find('.owl-stage').get(0);

      if (!outer || !stage) return;

      const outerWidth = Math.ceil(outer.getBoundingClientRect().width);
      if (!outerWidth) return;

      // +2px overlap يمنع ظهور أي فراغ أو طرف من السلايد التالي بسبب rounding/sub-pixel.
      const itemWidth = outerWidth + 2;
      const $items = $p.find('.owl-item');

      $p.css({
        height: 'auto',
        overflow: 'hidden',
        position: 'relative'
      });

      $(outer).css({
        width: outerWidth + 'px',
        maxWidth: '100%',
        overflow: 'hidden'
      });

      $items.each(function(){
        this.style.width = itemWidth + 'px';
        this.style.marginLeft = '0px';
        this.style.marginRight = '0px';
        this.style.paddingLeft = '0px';
        this.style.paddingRight = '0px';
        this.style.overflow = 'hidden';
      });

      stage.style.width = (itemWidth * $items.length) + 'px';
      stage.style.marginLeft = '0px';
      stage.style.marginRight = '0px';
      stage.style.paddingLeft = '0px';
      stage.style.paddingRight = '0px';

      $p.find('.lucodeia-slider-slide-item').css({
        width: 'calc(100% + 6px)',
        maxWidth: 'none',
        marginLeft: '-3px',
        marginRight: '-3px',
        borderRadius: 0,
        overflow: 'hidden'
      });

      $('.lucodeia-slider .owl-dots').removeClass('disabled').show();
    };

    // منع تهيئة مزدوجة لو كان الكود نُفّذ قبل ذلك من كاش/Minify.
    if ($owl.hasClass('owl-loaded')) {
      try {
        $owl.trigger('destroy.owl.carousel');
        $owl.removeClass('owl-loaded owl-hidden');
        $owl.find('.owl-stage-outer').children().unwrap();
      } catch (err) {}
    }

    $owl.on(
      'initialized.owl.carousel refreshed.owl.carousel resized.owl.carousel changed.owl.carousel translated.owl.carousel',
      function(){
        setTimeout(hardFixHeroWidths, 0);
        setTimeout(hardFixHeroWidths, 80);
      }
    );

    $owl.owlCarousel({
      rtl: true,
      items: 1,
      loop: true,
      autoplay: true,
      autoplayTimeout: 5000,
      autoplayHoverPause: true,
      nav: false,
      dots: true,
      smartSpeed: 600,

      margin: 0,
      stagePadding: 0,
      center: false,
      autoWidth: false,
      autoHeight: false,
      merge: false,

      mouseDrag: true,
      touchDrag: true,
      pullDrag: false,

      responsive: {
        0:    { items: 1, margin: 0, stagePadding: 0, center: false, autoWidth: false },
        768:  { items: 1, margin: 0, stagePadding: 0, center: false, autoWidth: false },
        1200: { items: 1, margin: 0, stagePadding: 0, center: false, autoWidth: false }
      }
    });

    setTimeout(hardFixHeroWidths, 100);
    setTimeout(hardFixHeroWidths, 500);
    setTimeout(hardFixHeroWidths, 1200);

    w.addEventListener('resize', function(){
      setTimeout(hardFixHeroWidths, 150);
      setTimeout(hardFixHeroWidths, 500);
    });
  });

  /* ===== Dropdown toggles ===== */
  d.addEventListener('click',e=>{
    const btn=e.target.closest('#sectionsDropdown,#genresDropdown,#statusDropdown,#typeDropdown,#seasonsDropdown'); if(!btn) return;
    e.preventDefault(); const dd=btn.closest('.dropdown'); d.querySelectorAll('.dropdown.open').forEach(x=>x!==dd&&x.classList.remove('open')); dd&&dd.classList.toggle('open');
  });

  /* ===== Close menu/overlay ===== */
  d.addEventListener('click',e=>{ if(e.target.closest('.menu-close,.menu-overlay')){ e.preventDefault(); location.hash=''; } });

  /* ===== Episode number filter (Arabic digits supported) ===== */
  d.addEventListener('DOMContentLoaded',()=>{
    const inp=d.getElementById('inputEpisode'); if(!inp) return;
    const dig=s=>String(s||'').replace(/[٠-٩]/g,d=> '٠١٢٣٤٥٦٧٨٩'.indexOf(d)), num=s=>(dig(s).match(/\d+/)||[''])[0];
    const filter=()=>{ const q=num(inp.value); d.querySelectorAll('#episodesList .themexblock').forEach(c=>{
      const n=num(c.querySelector('.badge.light-soft span')?.textContent||''); c.style.display=!q||q===n?'':'none';
    })};
    inp.addEventListener('input',filter);
  });

(function ($, d) {
  /* ===== Watch splash ===== */
  const show = () => {
    const s = d.getElementById('watch-splash');
    const t = d.getElementById('watch-tab-1');
    if (s) s.style.display = 'none';
    if (t) t.style.display = '';
  };

  d.addEventListener('DOMContentLoaded', () => {
    const t = d.getElementById('watch-tab-1');
    if (t) t.style.display = 'none';

    // نختار أول قائمة موجودة: episode-servers أو watch-servers
    const $list   = $('ul#episode-servers, ul#watch-servers').first();
    const $iframe = $('.videoWrapper iframe').first();

    // تهيئة أول سيرفر
    const $first = $list.find('li[data-watch]').first();
    if ($first.length) {
      // أضف ISActive إن لم تكن موجودة
      if (!$list.find('li.active').length) $first.addClass('active');

      const src = $first.data('watch');
      if ($iframe.length && src) $iframe.attr('src', src);
    }

    // تفويض الضغط على العناصر
    $list.on('click', 'li[data-watch]', function (e) {
      e.preventDefault();
      const src = $(this).data('watch');
      $(this).addClass('active').siblings().removeClass('active');
      if ($iframe.length && src) $iframe.attr('src', src);
      show(); // إخفاء الـ splash عند الاختيار
    });
  });

  // إخفاء splash بالماوس أو الكيبورد
 d.addEventListener('click', e => {
    if (!e.target.closest) return;

    /* لا تخفِ منطقة الإعلان عند الضغط عليه */
    if (e.target.closest('#video-ad-container')) {
        return;
    }

    if (e.target.closest('#watch-splash')) {
        show();
    }
});
  d.addEventListener('keydown', e => {
    const s = d.getElementById('watch-splash');
    if (!s) return;
    if (d.activeElement === s && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      show();
    }
  });
})(jQuery, document);


  /* ===== Typing marquee (BreakNewsLine) ===== */
  d.addEventListener('DOMContentLoaded',()=>{
    const arr=``.split(',').filter(Boolean), m=d.getElementById('marquee'); if(!m||!arr.length) return;
    let i=0; (function loop(){ m.textContent=''; const s=arr[i]; let k=0;
      (function type(){ k<s.length ? (m.textContent+=s[k++], setTimeout(type,40)) :
        setTimeout(()=>{ let r=s.length; (function del(){ r ? (m.textContent=s.slice(0,--r), setTimeout(del,40)) : (i=(i+1)%arr.length, loop()); })(); },3000);
      })();
    })();
  });

  /* ===== Theme toggle ===== */
  (()=>{ const ico=d.getElementById('theme-mode-icon'); if(!ico) return; const K='theme';
    const set=m=>{d.body.classList.toggle('dark',m==='dark'); ico.className=m==='dark'?'far fa-moon':'fas fa-sun'};
    set(localStorage.getItem(K)==='dark'?'dark':'light');
    ico.onclick=()=>{ const m=d.body.classList.contains('dark')?'light':'dark'; set(m); localStorage.setItem(K,m); };
  })();

  /* ===== Timed alert (24h) ===== */
  d.addEventListener('DOMContentLoaded',()=>{
    const a=d.getElementById('tme-alert'), b=d.getElementById('tme-close'), K='tmeAlertClosedTime', t=+localStorage.getItem(K)||0;
    if(a && Date.now()-t>864e5){ a.style.display='block'; b&&b.addEventListener('click',()=>{ a.style.opacity='0'; setTimeout(()=>a.style.display='none',300); localStorage.setItem(K,Date.now()); }); }
  });

})(window,document,jQuery);} else {
    console.error('Anime4up: jQuery is not loaded, sliders cannot start.');
}
