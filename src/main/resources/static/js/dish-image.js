'use strict';
(() => {
  const input = document.querySelector('[data-dish-image]');
  if (!input) return;
  const preview = document.getElementById('dish-image-preview');
  const status = document.getElementById('dish-image-status');
  let url;
  input.addEventListener('change', () => {
    if (url) URL.revokeObjectURL(url);
    input.setCustomValidity(''); preview.hidden = true; status.textContent = '';
    const file = input.files[0]; if (!file) return;
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) {
      input.setCustomValidity('Chọn ảnh JPG, PNG hoặc WebP không quá 5 MB.');
      status.textContent = input.validationMessage; input.reportValidity(); return;
    }
    url = URL.createObjectURL(file); preview.src = url; preview.hidden = false;
    status.textContent = 'Ảnh xem trước. Bấm tải ảnh để lưu thay đổi.';
  });
})();
