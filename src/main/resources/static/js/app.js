(function () {
    'use strict';

    const form = document.getElementById('search-form');
    const qInput = document.getElementById('q');
    const sizeSel = document.getElementById('size');
    const langSel = document.getElementById('lang');
    const safeSel = document.getElementById('safesearch');
    const nocacheChk = document.getElementById('nocache');
    const btn = form.querySelector('.search-btn');

    const summaryEl = document.getElementById('summary');
    const statusEl = document.getElementById('status');
    const resultsEl = document.getElementById('results');
    const pagerEl = document.getElementById('pager');

    let currentPage = 1;
    let lastQuery = null;
    let abortCtl = null;

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        const q = qInput.value.trim();
        if (!q) {
            qInput.focus();
            return;
        }
        currentPage = 1;
        runSearch(q, currentPage);
    });

    // Read ?q= on load so bookmarked URLs work.
    (function initFromUrl() {
        const params = new URLSearchParams(window.location.search);
        const q = params.get('q');
        if (q) {
            qInput.value = q;
            currentPage = Math.max(1, parseInt(params.get('page') || '1', 10));
            runSearch(q, currentPage);
        }
    })();

    function runSearch(q, page) {
        lastQuery = q;
        if (abortCtl) abortCtl.abort();
        abortCtl = new AbortController();

        const params = new URLSearchParams();
        params.set('q', q);
        params.set('page', String(page));
        params.set('size', sizeSel.value);
        params.set('lang', langSel.value);
        params.set('safesearch', safeSel.value);
        if (nocacheChk.checked) params.set('nocache', 'true');

        showLoading();
        btn.disabled = true;

        // Update address bar without reloading.
        const url = new URL(window.location.href);
        url.searchParams.set('q', q);
        url.searchParams.set('page', String(page));
        window.history.replaceState({}, '', url.toString());

        fetch('/api/search?' + params.toString(), { signal: abortCtl.signal })
            .then(function (resp) { return resp.json().then(function (j) { return { status: resp.status, body: j }; }); })
            .then(function (r) {
                btn.disabled = false;
                if (!r.body || r.body.code !== 0) {
                    const msg = (r.body && r.body.msg) || ('HTTP ' + r.status);
                    showError(msg, function () { runSearch(lastQuery, page); });
                    return;
                }
                render(r.body.data);
            })
            .catch(function (err) {
                btn.disabled = false;
                if (err && err.name === 'AbortError') return;
                showError(err.message || '网络异常', function () { runSearch(lastQuery, page); });
            });
    }

    function showLoading() {
        summaryEl.classList.add('hidden');
        resultsEl.innerHTML = '';
        pagerEl.classList.add('hidden');
        statusEl.classList.remove('hidden', 'error');
        statusEl.classList.add('loading');
        statusEl.textContent = '搜索中... 并发调用多个引擎';
    }

    function showError(msg, retryFn) {
        statusEl.classList.remove('hidden', 'loading');
        statusEl.classList.add('error');
        statusEl.textContent = '';
        const span = document.createElement('span');
        span.textContent = '搜索失败: ' + msg + ' ';
        statusEl.appendChild(span);
        if (typeof retryFn === 'function') {
            const a = document.createElement('a');
            a.href = '#';
            a.textContent = '重试';
            a.addEventListener('click', function (e) { e.preventDefault(); retryFn(); });
            statusEl.appendChild(a);
        }
    }

    function render(data) {
        statusEl.classList.add('hidden');
        statusEl.textContent = '';

        // Summary line.
        summaryEl.classList.remove('hidden');
        summaryEl.innerHTML = '';
        const info = document.createElement('span');
        info.textContent = '找到 ' + (data.total || 0) + ' 条结果 (' +
            ((data.elapsedMs || 0) / 1000).toFixed(2) + 's)';
        summaryEl.appendChild(info);
        if (data.cached) {
            const badge = document.createElement('span');
            badge.className = 'badge-cached';
            badge.textContent = 'CACHED';
            summaryEl.appendChild(badge);
        }
        if (data.providers) {
            Object.keys(data.providers).forEach(function (name) {
                const p = data.providers[name];
                const s = document.createElement('span');
                s.className = 'chip';
                s.textContent = name + ':' + p.status + (p.elapsedMs ? ' ' + p.elapsedMs + 'ms' : '') +
                    (p.count ? ' (' + p.count + ')' : '');
                summaryEl.appendChild(s);
            });
        }

        // Result cards.
        resultsEl.innerHTML = '';
        if (!data.results || data.results.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'status';
            empty.textContent = '没有匹配的结果，试试其他关键词。';
            resultsEl.appendChild(empty);
        } else {
            data.results.forEach(function (r) {
                resultsEl.appendChild(renderCard(r));
            });
        }

        // Pager.
        renderPager(data);
    }

    function renderCard(r) {
        const card = document.createElement('article');
        card.className = 'result';

        const h = document.createElement('h3');
        h.className = 'title';
        const a = document.createElement('a');
        a.href = r.url;
        a.target = '_blank';
        a.rel = 'noopener noreferrer nofollow';
        a.textContent = r.title || r.url;
        h.appendChild(a);
        card.appendChild(h);

        const u = document.createElement('div');
        u.className = 'url';
        u.textContent = displayUrl(r.url);
        card.appendChild(u);

        if (r.snippet) {
            const p = document.createElement('p');
            p.className = 'snippet';
            p.textContent = r.snippet;
            card.appendChild(p);
        }

        const meta = document.createElement('div');
        meta.className = 'meta';
        (r.sources || (r.source ? [r.source] : [])).forEach(function (s) {
            const b = document.createElement('span');
            b.className = 'badge badge-' + (['google', 'bing', 'searxng'].indexOf(s) >= 0 ? s : 'unknown');
            b.textContent = s;
            meta.appendChild(b);
        });
        if (typeof r.score === 'number') {
            const sc = document.createElement('span');
            sc.className = 'score';
            sc.textContent = 'score=' + r.score.toFixed(4);
            meta.appendChild(sc);
        }
        card.appendChild(meta);

        return card;
    }

    function renderPager(data) {
        pagerEl.innerHTML = '';
        const total = data.total || 0;
        const size = data.size || 10;
        const page = data.page || 1;
        const pages = Math.max(1, Math.ceil(total / size));
        if (pages <= 1) {
            pagerEl.classList.add('hidden');
            return;
        }
        pagerEl.classList.remove('hidden');

        const prev = document.createElement('button');
        prev.textContent = '< 上一页';
        prev.disabled = page <= 1;
        prev.addEventListener('click', function () { runSearch(lastQuery, page - 1); });
        pagerEl.appendChild(prev);

        const info = document.createElement('span');
        info.className = 'info';
        info.textContent = '第 ' + page + ' / ' + pages + ' 页';
        pagerEl.appendChild(info);

        const next = document.createElement('button');
        next.textContent = '下一页 >';
        next.disabled = page >= pages || page * size >= total;
        next.addEventListener('click', function () { runSearch(lastQuery, page + 1); });
        pagerEl.appendChild(next);
    }

    function displayUrl(u) {
        try {
            const url = new URL(u);
            return url.hostname + (url.pathname === '/' ? '' : url.pathname);
        } catch (e) {
            return u;
        }
    }
})();
