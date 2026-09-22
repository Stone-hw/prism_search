(function () {
    'use strict';

    // ===== DOM 元素 =====
    const homeView = document.getElementById('home-view');
    const resultView = document.getElementById('result-view');

    // 首页表单
    const homeForm = document.getElementById('home-search-form');
    const homeQ = document.getElementById('home-q');
    const homeSize = document.getElementById('home-size');
    const homeLang = document.getElementById('home-lang');
    const homeSafe = document.getElementById('home-safesearch');
    const homeNocache = document.getElementById('home-nocache');
    const homeClearBtn = homeForm.querySelector('.clear-btn');
    const toggleAdvancedHome = document.getElementById('toggle-advanced-home');
    const advancedHome = document.getElementById('advanced-home');

    // 结果页表单
    const resultForm = document.getElementById('result-search-form');
    const resultQ = document.getElementById('result-q');
    const resultSize = document.getElementById('result-size');
    const resultLang = document.getElementById('result-lang');
    const resultSafe = document.getElementById('result-safesearch');
    const resultNocache = document.getElementById('result-nocache');
    const resultClearBtn = resultForm.querySelector('.clear-btn');
    const toggleAdvancedResult = document.getElementById('toggle-advanced-result');
    const advancedResult = document.getElementById('advanced-result');
    const backHome = document.getElementById('back-home');

    const summaryEl = document.getElementById('summary');
    const statusEl = document.getElementById('status');
    const resultsEl = document.getElementById('results');
    const pagerEl = document.getElementById('pager');

    let currentPage = 1;
    let lastQuery = null;
    let abortCtl = null;

    // ===== 视图切换 =====
    function showHome() {
        homeView.classList.remove('hidden');
        resultView.classList.add('hidden');
        homeQ.focus();
    }

    function showResults() {
        homeView.classList.add('hidden');
        resultView.classList.remove('hidden');
    }

    backHome.addEventListener('click', function (e) {
        e.preventDefault();
        showHome();
        history.pushState({}, '', window.location.pathname);
    });

    // ===== 高级选项折叠 =====
    toggleAdvancedHome.addEventListener('click', function () {
        advancedHome.classList.toggle('hidden');
    });
    toggleAdvancedResult.addEventListener('click', function () {
        advancedResult.classList.toggle('hidden');
    });

    // ===== 清除按钮 =====
    homeQ.addEventListener('input', function () {
        homeClearBtn.classList.toggle('hidden', !homeQ.value);
    });
    homeClearBtn.addEventListener('click', function () {
        homeQ.value = '';
        homeClearBtn.classList.add('hidden');
        homeQ.focus();
    });
    resultQ.addEventListener('input', function () {
        resultClearBtn.classList.toggle('hidden', !resultQ.value);
    });
    resultClearBtn.addEventListener('click', function () {
        resultQ.value = '';
        resultClearBtn.classList.add('hidden');
        resultQ.focus();
    });

    // ===== 同步表单值 =====
    function syncFromHome() {
        resultSize.value = homeSize.value;
        resultLang.value = homeLang.value;
        resultSafe.value = homeSafe.value;
        resultNocache.checked = homeNocache.checked;
    }

    function syncToHome() {
        homeSize.value = resultSize.value;
        homeLang.value = resultLang.value;
        homeSafe.value = resultSafe.value;
        homeNocache.checked = resultNocache.checked;
    }

    // ===== 表单提交 =====
    homeForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var q = homeQ.value.trim();
        if (!q) { homeQ.focus(); return; }
        syncFromHome();
        currentPage = 1;
        showResults();
        runSearch(q, currentPage);
    });

    resultForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var q = resultQ.value.trim();
        if (!q) { resultQ.focus(); return; }
        currentPage = 1;
        runSearch(q, currentPage);
    });

    // ===== URL 初始化 =====
    (function initFromUrl() {
        var params = new URLSearchParams(window.location.search);
        var q = params.get('q');
        if (q) {
            homeQ.value = q;
            resultQ.value = q;
            homeClearBtn.classList.toggle('hidden', !q);
            resultClearBtn.classList.toggle('hidden', !q);
            currentPage = Math.max(1, parseInt(params.get('page') || '1', 10));
            syncFromHome();
            showResults();
            runSearch(q, currentPage);
        }
    })();

    // ===== 搜索执行 =====
    function runSearch(q, page) {
        lastQuery = q;
        if (abortCtl) abortCtl.abort();
        abortCtl = new AbortController();

        var params = new URLSearchParams();
        params.set('q', q);
        params.set('page', String(page));
        params.set('size', resultSize.value);
        params.set('lang', resultLang.value);
        params.set('safesearch', resultSafe.value);
        if (resultNocache.checked) params.set('nocache', 'true');

        showLoading();

        // 更新地址栏
        var url = new URL(window.location.href);
        url.searchParams.set('q', q);
        url.searchParams.set('page', String(page));
        window.history.replaceState({}, '', url.toString());

        fetch('/api/search?' + params.toString(), { signal: abortCtl.signal })
            .then(function (resp) {
                return resp.json().then(function (j) { return { status: resp.status, body: j }; });
            })
            .then(function (r) {
                if (!r.body || r.body.code !== 0) {
                    var msg = (r.body && r.body.msg) || ('HTTP ' + r.status);
                    showError(msg, function () { runSearch(lastQuery, page); });
                    return;
                }
                render(r.body.data);
            })
            .catch(function (err) {
                if (err && err.name === 'AbortError') return;
                showError(err.message || '网络异常', function () { runSearch(lastQuery, page); });
            });
    }

    // ===== 状态显示 =====
    function showLoading() {
        summaryEl.classList.add('hidden');
        resultsEl.innerHTML = '';
        pagerEl.classList.add('hidden');
        statusEl.classList.remove('hidden', 'error');
        statusEl.classList.add('loading');
        statusEl.textContent = '搜索中...';
    }

    function showError(msg, retryFn) {
        statusEl.classList.remove('hidden', 'loading');
        statusEl.classList.add('error');
        statusEl.textContent = '';
        var span = document.createElement('span');
        span.textContent = '搜索失败: ' + msg + ' ';
        statusEl.appendChild(span);
        if (typeof retryFn === 'function') {
            var a = document.createElement('a');
            a.href = '#';
            a.textContent = '重试';
            a.style.color = 'var(--accent)';
            a.addEventListener('click', function (e) { e.preventDefault(); retryFn(); });
            statusEl.appendChild(a);
        }
    }

    // ===== 渲染结果 =====
    function render(data) {
        statusEl.classList.add('hidden');
        statusEl.textContent = '';

        // 摘要
        summaryEl.classList.remove('hidden');
        summaryEl.innerHTML = '';
        var info = document.createElement('span');
        info.textContent = '约 ' + (data.total || 0) + ' 条结果 (' +
            ((data.elapsedMs || 0) / 1000).toFixed(2) + ' 秒)';
        summaryEl.appendChild(info);

        if (data.cached) {
            var badge = document.createElement('span');
            badge.className = 'badge-cached';
            badge.textContent = '缓存';
            summaryEl.appendChild(badge);
        }

        if (data.providers) {
            Object.keys(data.providers).forEach(function (name) {
                var p = data.providers[name];
                var s = document.createElement('span');
                s.className = 'chip';
                s.textContent = name + ' ' + p.count + '条';
                summaryEl.appendChild(s);
            });
        }

        // 结果列表
        resultsEl.innerHTML = '';
        if (!data.results || data.results.length === 0) {
            var empty = document.createElement('div');
            empty.className = 'status';
            empty.textContent = '没有找到相关结果，请尝试其他关键词。';
            resultsEl.appendChild(empty);
        } else {
            data.results.forEach(function (r) {
                resultsEl.appendChild(renderCard(r));
            });
        }

        renderPager(data);
    }

    function renderCard(r) {
        var card = document.createElement('article');
        card.className = 'result';

        // URL 显示
        var u = document.createElement('div');
        u.className = 'url';
        u.textContent = displayUrl(r.url);
        card.appendChild(u);

        // 标题
        var h = document.createElement('h3');
        h.className = 'title';
        var a = document.createElement('a');
        a.href = r.url;
        a.target = '_blank';
        a.rel = 'noopener noreferrer nofollow';
        a.textContent = r.title || r.url;
        h.appendChild(a);
        card.appendChild(h);

        // 摘要
        if (r.snippet) {
            var p = document.createElement('p');
            p.className = 'snippet';
            p.textContent = r.snippet;
            card.appendChild(p);
        }

        // 元信息
        var meta = document.createElement('div');
        meta.className = 'meta';
        (r.sources || (r.source ? [r.source] : [])).forEach(function (s) {
            var b = document.createElement('span');
            b.className = 'badge badge-' + (['google', 'bing', 'searxng', 'baidu'].indexOf(s) >= 0 ? s : 'unknown');
            b.textContent = s;
            meta.appendChild(b);
        });
        if (typeof r.score === 'number') {
            var sc = document.createElement('span');
            sc.className = 'score';
            sc.textContent = r.score.toFixed(4);
            meta.appendChild(sc);
        }
        card.appendChild(meta);

        return card;
    }

    function renderPager(data) {
        pagerEl.innerHTML = '';
        var total = data.total || 0;
        var size = data.size || 10;
        var page = data.page || 1;
        var pages = Math.max(1, Math.ceil(total / size));

        if (pages <= 1) {
            pagerEl.classList.add('hidden');
            return;
        }
        pagerEl.classList.remove('hidden');

        var prev = document.createElement('button');
        prev.textContent = '< 上一页';
        prev.disabled = page <= 1;
        prev.addEventListener('click', function () { runSearch(lastQuery, page - 1); });
        pagerEl.appendChild(prev);

        var info = document.createElement('span');
        info.className = 'info';
        info.textContent = '第 ' + page + ' / ' + pages + ' 页';
        pagerEl.appendChild(info);

        var next = document.createElement('button');
        next.textContent = '下一页 >';
        next.disabled = page >= pages || page * size >= total;
        next.addEventListener('click', function () { runSearch(lastQuery, page + 1); });
        pagerEl.appendChild(next);
    }

    function displayUrl(u) {
        try {
            var url = new URL(u);
            return url.hostname + (url.pathname === '/' ? '' : url.pathname);
        } catch (e) {
            return u;
        }
    }
})();
