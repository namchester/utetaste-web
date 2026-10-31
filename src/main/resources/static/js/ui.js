'use strict';

// Progressive enhancement: native forms, URLs and server-side validation remain authoritative.
(() => {
  const sidebar = document.querySelector('#staff-navigation');
  const opener = document.querySelector('[data-nav-toggle]');
  const workspace = document.querySelector('.workspace');
  const mobile = matchMedia('(max-width: 992px)');
  if (sidebar && opener) {
    const backdrop = document.createElement('button');
    backdrop.type = 'button'; backdrop.className = 'nav-backdrop no-print'; backdrop.hidden = true;
    backdrop.tabIndex = -1; backdrop.setAttribute('aria-label', 'Đóng điều hướng'); document.body.append(backdrop);
    let opened = false;
    const setOpen = (requested, restoreFocus = false) => {
      opened = requested && mobile.matches;
      sidebar.classList.toggle('open', opened);
      sidebar.inert = mobile.matches && !opened;
      workspace.inert = opened;
      backdrop.hidden = !opened;
      document.body.classList.toggle('nav-open', opened);
      opener.setAttribute('aria-expanded', String(opened));
      opener.setAttribute('aria-label', opened ? 'Đóng điều hướng' : 'Mở điều hướng');
      if (opened) {
        sidebar.setAttribute('role', 'dialog'); sidebar.setAttribute('aria-modal', 'true');
        sidebar.querySelector('[data-nav-close]').focus();
      } else {
        sidebar.removeAttribute('role'); sidebar.removeAttribute('aria-modal');
        if (restoreFocus && mobile.matches) opener.focus();
      }
    };
    opener.addEventListener('click', () => setOpen(!opened));
    sidebar.querySelector('[data-nav-close]').addEventListener('click', () => setOpen(false, true));
    backdrop.addEventListener('click', () => setOpen(false, true));
    sidebar.addEventListener('click', event => { if (event.target.closest('a[href]')) setOpen(false); });
    document.addEventListener('keydown', event => {
      if (!opened) return;
      if (event.key === 'Escape') { event.preventDefault(); setOpen(false, true); }
      if (event.key !== 'Tab') return;
      const focusable = [...sidebar.querySelectorAll('a[href],button:not(:disabled),input:not(:disabled),select:not(:disabled),[tabindex="0"]')]
        .filter(item => item.getClientRects().length);
      const first = focusable[0], last = focusable.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    });
    mobile.addEventListener('change', () => setOpen(false, sidebar.contains(document.activeElement)));
    setOpen(false);
    sidebar.querySelectorAll('nav a.active').forEach(link => link.setAttribute('aria-current', 'page'));
  }

  const publicToggle = document.querySelector('[data-public-nav]');
  if (publicToggle) {
    const header = publicToggle.closest('header');
    const setOpen = open => {
      header.classList.toggle('public-menu-open', open);
      publicToggle.setAttribute('aria-expanded', String(open));
      publicToggle.setAttribute('aria-label', open ? 'Đóng điều hướng trang chủ' : 'Mở điều hướng trang chủ');
    };
    publicToggle.addEventListener('click', () => setOpen(publicToggle.getAttribute('aria-expanded') !== 'true'));
    header.querySelector('nav').addEventListener('click', event => { if (event.target.closest('a')) setOpen(false); });
    header.addEventListener('keydown', event => {
      if (event.key === 'Escape' && publicToggle.getAttribute('aria-expanded') === 'true') {
        setOpen(false); publicToggle.focus();
      }
    });
    matchMedia('(max-width: 1100px)').addEventListener('change', () => setOpen(false));
  }

  document.addEventListener('keydown', event => {
    const region = event.target;
    if (!region.matches('.table-responsive') || event.altKey || event.ctrlKey || event.metaKey) return;
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key) || region.scrollWidth <= region.clientWidth) return;
    event.preventDefault();
    region.scrollBy({left: event.key === 'ArrowRight' ? 100 : -100, behavior: 'instant'});
  });

  let pendingForms = new WeakSet();
  const buttons = new WeakMap();
  document.addEventListener('submit', event => {
    const form = event.target;
    if (event.defaultPrevented || !(form instanceof HTMLFormElement)) return;
    if (pendingForms.has(form)) { event.preventDefault(); return; }
    pendingForms.add(form); form.setAttribute('aria-busy', 'true');
    const button = event.submitter || form.querySelector('button:not([type="button"]), input[type="submit"]');
    if (button) {
      buttons.set(button, button.innerHTML);
      button.setAttribute('aria-busy', 'true'); button.setAttribute('aria-disabled', 'true');
      if (button.tagName === 'BUTTON') button.textContent = 'Đang xử lý…';
    }
    // Controls stay enabled: disabling a control here would omit its value from POST.
  });
  window.addEventListener('pageshow', () => {
    pendingForms = new WeakSet();
    document.querySelectorAll('form[aria-busy="true"]').forEach(form => form.removeAttribute('aria-busy'));
    document.querySelectorAll('form [aria-busy="true"]').forEach(button => {
      if (buttons.has(button)) button.innerHTML = buttons.get(button);
      button.removeAttribute('aria-busy'); button.removeAttribute('aria-disabled');
    });
  });

  let errorSequence = 0;
  const errors = new WeakMap();
  function clearError(control) {
    const error = errors.get(control);
    if (!error) return;
    control.removeAttribute('aria-invalid');
    const describedBy = (control.getAttribute('aria-describedby') || '').split(' ').filter(id => id && id !== error.id);
    if (describedBy.length) control.setAttribute('aria-describedby', describedBy.join(' '));
    else control.removeAttribute('aria-describedby');
    error.remove(); errors.delete(control);
  }
  document.addEventListener('invalid', event => {
    const control = event.target;
    if (!(control instanceof HTMLElement) || !('validationMessage' in control)) return;
    clearError(control);
    const error = document.createElement('span');
    error.id = 'field-error-' + ++errorSequence; error.className = 'field-error';
    error.textContent = control.validationMessage; error.setAttribute('role', 'alert');
    (control.closest('.input-icon-wrap') || control).after(error);
    control.setAttribute('aria-invalid', 'true');
    control.setAttribute('aria-describedby', [control.getAttribute('aria-describedby'), error.id].filter(Boolean).join(' '));
    errors.set(control, error);
  }, true);
  for (const type of ['input', 'change']) {
    document.addEventListener(type, event => {
      const control = event.target;
      if (!control.matches('input,select,textarea')) return;
      if (control.validity.valid) clearError(control);
      if (control.form) control.form.dataset.dirty = 'true';
    });
  }
  document.addEventListener('reset', event => {
    event.target.querySelectorAll('[aria-invalid="true"]').forEach(clearError);
    delete event.target.dataset.dirty;
  });
})();
