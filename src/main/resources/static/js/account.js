'use strict';
document.querySelectorAll('[data-password-confirm]').forEach(form => {
  const password = form.querySelector('[name="password"]');
  const confirm = form.querySelector('[name="confirmPassword"]');
  const validate = () => {
    password.setCustomValidity(new TextEncoder().encode(password.value).length > 72 ? 'Mật khẩu không được vượt quá 72 byte UTF-8.' : '');
    confirm.setCustomValidity(confirm.value !== password.value ? 'Xác nhận mật khẩu chưa khớp.' : '');
  };
  password.addEventListener('input', validate); confirm.addEventListener('input', validate);
});
