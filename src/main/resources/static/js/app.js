'use strict';
window.UT = {
  toast(message, error = false) {
    const el = document.createElement('div');
    el.className = 'app-toast' + (error ? ' error' : '');
    el.setAttribute('role', error ? 'alert' : 'status');
    const text = document.createElement('span'); text.className = 'toast-message'; text.textContent = message;
    const close = document.createElement('button'); close.type = 'button'; close.className = 'toast-dismiss';
    close.setAttribute('aria-label', 'Đóng thông báo'); close.textContent = '×';
    close.addEventListener('click', () => el.remove());
    el.append(text, close); document.querySelector('#toast-area')?.append(el);
    let timer;
    const pause = () => clearTimeout(timer);
    const resume = () => { pause(); timer = setTimeout(() => { if (!el.matches(':hover, :focus-within')) el.remove(); }, 8000); };
    el.addEventListener('mouseenter', pause); el.addEventListener('mouseleave', resume);
    el.addEventListener('focusin', pause); el.addEventListener('focusout', resume); resume();
  },
  busy(button, active, label = 'Đang xử lý…') {
    if (active) {
      const state = {disabled: button.disabled, html: button.innerHTML};
      button.disabled = true; button.setAttribute('aria-busy', 'true'); button.textContent = label;
      return () => { button.disabled = state.disabled; button.removeAttribute('aria-busy'); button.innerHTML = state.html; };
    }
  },
  money(value) { return new Intl.NumberFormat('vi-VN').format(value) + ' ₫'; },
  async request(url, body) {
    const csrf = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    UT.pendingRequests = (UT.pendingRequests || 0) + 1;
    try {
      const response = await fetch(url, {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json', [header || 'X-XSRF-TOKEN']: csrf || ''}, body: JSON.stringify(body)});
      let result; try { result = await response.json(); } catch (_) { result = {}; }
      if (!response.ok) throw new Error(result.message || (response.status === 403 ? 'Bạn chưa có quyền hoặc phiên bảo mật đã hết hạn. Hãy tải lại trang.' : 'Không thể xử lý yêu cầu. Vui lòng thử lại.'));
      return result;
    } catch (error) {
      if (error instanceof TypeError) throw new Error('Không kết nối được máy chủ. Vui lòng kiểm tra mạng và thử lại.');
      throw error;
    } finally { UT.pendingRequests--; }
  },
  async refresh() {
    if (!document.querySelector('[data-live-zone]') || window.UT.refreshing || UT.pendingRequests || document.querySelector('.modal.show')) return;
    // Preserve form entries while the user edits a table, a bill or another form.
    if (document.activeElement?.matches('input,select,textarea') || document.querySelector('details[open]')) return;
    window.UT.refreshing = true;
    try {
      const response = await fetch(location.href, {headers: {'X-Requested-With': 'XMLHttpRequest'}});
      if (!response.ok || response.redirected) return;
      const html = new DOMParser().parseFromString(await response.text(), 'text/html');
      document.querySelectorAll('[data-live-zone]').forEach(zone => {
        if (zone.querySelector('form[data-dirty], form[aria-busy="true"], input[form="qr-batch"]:checked')) return;
        const incoming = html.getElementById(zone.id);
        if (!incoming) return;
        const active = document.activeElement;
        const focused = zone.contains(active);
        const order = active?.dataset.order;
        const id = active?.id;
        zone.replaceWith(incoming);
        if (focused) {
          const target = (id && incoming.querySelector('#' + CSS.escape(id)))
            || (order && incoming.querySelector('[data-order="' + CSS.escape(order) + '"]'));
          if (target) target.focus({preventScroll: true});
          else { incoming.tabIndex = -1; incoming.focus({preventScroll: true}); }
        }
      });
    } catch (_) { /* The periodic fallback will retry when the connection returns. */ }
    finally { window.UT.refreshing = false; }
  }
};
document.addEventListener('submit', e => {
  if (e.target.matches('[data-confirm]') && !window.confirm(e.target.dataset.confirm)) e.preventDefault();
});
document.addEventListener('change', e => { if (e.target.matches('[data-auto-submit]')) e.target.form.requestSubmit(); });
document.addEventListener('click', async e => {
  if (e.target.closest('[data-print]')) window.print();
  if (e.target.closest('[data-back]')) history.length > 1 ? history.back() : location.assign('/');
  const status = e.target.closest('[data-status][data-order]');
  if (status) {
    if (status.dataset.status === 'CANCELLED' && !confirm('Hủy toàn bộ lượt gọi món này?')) return;
    const restore = UT.busy(status, true);
    try { await UT.request('/api/staff/orders/' + status.dataset.order + '/status', {status: status.dataset.status}); UT.toast('Đã cập nhật trạng thái đơn.'); await UT.refresh(); }
    catch (err) { UT.toast(err.message, true); } finally { restore(); }
  }
  const call = e.target.closest('[data-call]');
  if (call) { const restore = UT.busy(call, true, 'Đang gửi…'); try {const res = await UT.request('/api/guest/call', {message: call.dataset.call}); UT.toast(res.message);} catch (err) {UT.toast(err.message,true);} finally {setTimeout(restore,3000);} }
});
(() => {
  const type = document.body.dataset.live;
  if (!type) return;
  let timer, socket, retries = 0, stopped = false;
  const indicator = document.querySelector('[data-live-indicator]');
  function connection(connected) {
    if (!indicator) return;
    indicator.dataset.state = connected ? 'connected' : 'reconnecting';
    indicator.textContent = connected ? '● Cập nhật trực tiếp' : '○ Đang kết nối lại';
  }
  function connect() {
    if (stopped) return;
    socket = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/' + type);
    socket.onopen = () => {retries = 0; connection(true); UT.refresh();};
    socket.onmessage = event => {
      let data; try {data = JSON.parse(event.data);} catch (_) {return;}
      if (data.type === 'heartbeat') return;
      if (data.type === 'session.closed' && type === 'guest' && location.pathname === '/guest/menu') {location.assign('/guest/orders');return;}
      clearTimeout(timer);timer=setTimeout(()=>UT.refresh(),250);
    };
    socket.onclose = () => {connection(false); if(!stopped)setTimeout(connect,Math.min(30000,1500 * 2 ** Math.min(retries++,4)));};
    socket.onerror = () => socket.close();
  }
  connect();
  setInterval(()=>{if(document.visibilityState==='visible')UT.refresh();},15000);
  window.addEventListener('pagehide',()=>{stopped=true;socket?.close();});
})();
