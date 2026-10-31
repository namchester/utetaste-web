'use strict';
(() => {
  for (const selector of ['main .alert-success', 'main .alert-danger']) {
    const message = document.querySelector(selector)?.textContent.trim();
    if (message) UT.toast(message, selector.endsWith('alert-danger'));
  }
  const approved = new WeakSet();
  let inFlight = new WeakSet();
  window.addEventListener("pageshow", () => {inFlight = new WeakSet();});
  let form, submitter, hiding = false, reopen = false;
  const host = document.createElement('div');
  host.className = 'modal fade'; host.tabIndex = -1;
  host.setAttribute('aria-labelledby', 'admin-confirm-title'); host.setAttribute('aria-hidden', 'true');
  host.innerHTML = '<div class="modal-dialog modal-dialog-centered"><div class="modal-content"><div class="modal-header"><h2 class="modal-title fs-5" id="admin-confirm-title">Xác nhận thao tác</h2><button class="btn-close" type="button" data-bs-dismiss="modal" aria-label="Đóng"></button></div><div class="modal-body"><p data-confirm-message></p></div><div class="modal-footer"><button type="button" class="btn btn-light" data-bs-dismiss="modal">Hủy</button><button type="button" class="btn btn-danger" data-confirm-accept>Xác nhận</button></div></div></div>';
  document.body.append(host);
  const modal = new bootstrap.Modal(host);
  document.addEventListener('submit', event => {
    const target = event.target;
    if (!target.matches('[data-admin-confirm]')) return;
    if (target.id === 'dish-bulk' && !target.querySelector('input[name="ids"]:checked')) {
      event.preventDefault(); event.stopImmediatePropagation();
      UT.toast('Chọn ít nhất một món trước khi cập nhật.', true); return;
    }
    if (inFlight.has(target)) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    if (approved.has(target)) {
      approved.delete(target); inFlight.add(target); target.setAttribute('aria-busy', 'true');
      // Keep submit controls enabled so their values and formaction survive native submission.
      return;
    }
    event.preventDefault(); event.stopImmediatePropagation();
    form = target; submitter = event.submitter;
    const deleting = target.hasAttribute('data-admin-delete') || submitter?.hasAttribute('data-bulk-delete');
    let message = target.dataset.adminConfirm;
    if (target.id === 'dish-bulk' && deleting) {
      const selected = [...target.querySelectorAll('input[name="ids"]:checked')];
      const names = selected.map(input => input.getAttribute('aria-label')?.replace(/^Chọn /, '') || '#' + input.value);
      message = 'Xóa ' + selected.length + ' món đã chọn trên trang này: ' + names.join(', ') +
        '? Không thể khôi phục bản ghi đã xóa. Món có đơn hàng hoặc Favorites được giữ lại. Không xóa ảnh Cloudinary.';
    }
    host.querySelector('[data-confirm-message]').textContent = message;
    host.querySelector('#admin-confirm-title').textContent = deleting ? 'Xác nhận xóa' : 'Xác nhận thao tác';
    const accept = host.querySelector('[data-confirm-accept]');
    accept.textContent = deleting ? 'Xác nhận xóa' : 'Xác nhận';
    accept.disabled = false;
    if (hiding) reopen = true;
    else modal.show();
  }, true);
  host.querySelector('[data-confirm-accept]').addEventListener('click', event => {
    event.currentTarget.disabled = true;
    if (!form?.isConnected) { modal.hide(); return; }
    const target = form, button = submitter;
    approved.add(target); form = null; modal.hide();
    target.requestSubmit(button || undefined);
  });
  host.addEventListener('hide.bs.modal', () => { hiding = true; });
  host.addEventListener('hidden.bs.modal', () => {
    hiding = false;
    // A user can submit another action while the previous modal is fading out.
    // Queue it until Bootstrap completes the transition, preserving the new form.
    if (reopen && form?.isConnected) { reopen = false; modal.show(); return; }
    reopen = false; submitter?.focus(); form = null;
  });
  const dishBulk = document.querySelector('#dish-bulk');
  function selectionSummary() {
    if (!dishBulk) return;
    const all = [...dishBulk.querySelectorAll('input[name="ids"]')];
    const count = all.filter(input => input.checked).length;
    const master = dishBulk.querySelector('[data-select-dishes]');
    master.checked = all.length > 0 && count === all.length;
    master.indeterminate = count > 0 && count < all.length;
    master.disabled = all.length === 0;
    dishBulk.querySelectorAll('[data-bulk-submit]').forEach(button => {button.disabled = count === 0;});
    dishBulk.querySelector('[data-dish-selection-count]').textContent = 'Đã chọn ' + count + ' món';
  }
  dishBulk?.addEventListener('change', event => {
    if (event.target.matches('[data-select-dishes]')) {
      dishBulk.querySelectorAll('input[name="ids"]').forEach(input => { input.checked = event.target.checked; });
    }
    selectionSummary();
  });
  selectionSummary();
  document.querySelector('[data-select-tables]')?.addEventListener('change', event => {
    document.querySelectorAll('input[form="qr-batch"]').forEach(input => { input.checked = event.target.checked; });
  });
  document.querySelector('#qr-batch')?.addEventListener('submit', event => {
    const count = document.querySelectorAll('input[form="qr-batch"]:checked').length;
    if (count < 1 || count > 50) { event.preventDefault(); UT.toast('Chọn từ 1 đến 50 bàn để in QR.', true); }
  });
})();
