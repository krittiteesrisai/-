'use strict';
document.addEventListener('DOMContentLoaded', () => {
  const menuButton = document.querySelector('[data-menu-toggle]');
  const navigation = document.getElementById('site-navigation');
  if (menuButton && navigation) {
    const closeMenu = () => { menuButton.setAttribute('aria-expanded', 'false'); navigation.classList.remove('is-open'); };
    menuButton.addEventListener('click', () => {
      const open = menuButton.getAttribute('aria-expanded') !== 'true';
      menuButton.setAttribute('aria-expanded', String(open)); navigation.classList.toggle('is-open', open);
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && navigation.classList.contains('is-open')) { closeMenu(); menuButton.focus(); }
    });
    navigation.addEventListener('click', (event) => { if (event.target.closest('a')) closeMenu(); });
  }
  document.querySelectorAll('.account-menu').forEach((menu) => {
    menu.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && menu.open) { menu.open = false; menu.querySelector('summary')?.focus(); }
    });
  });
  document.querySelectorAll('[data-quantity-control]').forEach((group) => {
    const input = group.querySelector('input[type="number"]');
    if (!input) return;
    group.querySelectorAll('[data-quantity-change]').forEach((button) => {
      button.addEventListener('click', () => {
        if (input.disabled || input.readOnly) return;
        const min = Number(input.min || 1); const max = input.max ? Number(input.max) : Infinity;
        const current = Number(input.value) || min;
        input.value = String(Math.min(max, Math.max(min, current + Number(button.dataset.quantityChange))));
        input.dispatchEvent(new Event('input', {bubbles: true})); input.dispatchEvent(new Event('change', {bubbles: true}));
      });
    });
  });
  document.querySelectorAll('[data-equipment-option]').forEach((option) => {
    const checkbox = option.querySelector('input[type="checkbox"]'); const quantity = option.querySelector('input[type="number"]');
    if (!checkbox || !quantity) return;
    const update = () => { quantity.disabled = !checkbox.checked; option.classList.toggle('is-selected', checkbox.checked); };
    checkbox.addEventListener('change', update); update();
  });
  document.querySelectorAll('form[data-submit-once]').forEach((form) => {
    form.addEventListener('submit', (event) => {
      if (event.defaultPrevented) return;
      form.querySelectorAll('button[type="submit"]').forEach((button) => { button.disabled = true; button.dataset.originalLabel = button.textContent; button.textContent = 'กำลังดำเนินการ…'; });
    });
  });
  document.querySelectorAll('form[data-confirm]').forEach((form) => {
    form.addEventListener('submit', (event) => {
      if (!window.confirm(form.dataset.confirm)) event.preventDefault();
    }, true);
  });
  document.querySelectorAll('[data-confirm-dialog]').forEach((trigger) => {
    const dialog = document.getElementById(trigger.dataset.confirmDialog);
    if (dialog && typeof dialog.showModal === 'function') trigger.addEventListener('click', () => dialog.showModal());
  });
  document.querySelectorAll('[data-dialog-close]').forEach((button) => button.addEventListener('click', () => button.closest('dialog')?.close()));
  document.querySelectorAll('[data-gallery]').forEach((gallery) => {
    const track = gallery.querySelector('[data-gallery-track]');
    if (!track) return;
    const slides = Array.from(track.querySelectorAll('img'));
    const total = slides.length;
    const prev = gallery.querySelector('[data-gallery-prev]');
    const next = gallery.querySelector('[data-gallery-next]');
    const count = gallery.querySelector('[data-gallery-count]');
    const current = () => Math.round(track.scrollLeft / Math.max(track.clientWidth, 1));
    const go = (index) => track.scrollTo({left: Math.max(0, Math.min(total - 1, index)) * track.clientWidth});
    if (prev && next) {
      const update = () => {
        const index = current();
        prev.disabled = index <= 0; next.disabled = index >= total - 1;
        if (count) count.textContent = `${index + 1} / ${total}`;
      };
      prev.addEventListener('click', () => go(current() - 1));
      next.addEventListener('click', () => go(current() + 1));
      track.addEventListener('keydown', (event) => {
        if (event.key === 'ArrowLeft') { event.preventDefault(); go(current() - 1); }
        if (event.key === 'ArrowRight') { event.preventDefault(); go(current() + 1); }
      });
      track.addEventListener('scroll', () => window.requestAnimationFrame(update), {passive: true});
      update();
    }

    // ดูเต็มจอ + ซูม: กดที่รูปหรือปุ่มขยาย, ในหน้าเต็มจอกดที่รูปเพื่อซูม 2 เท่าแล้วลากเลื่อนดู
    const lightbox = gallery.querySelector('[data-lightbox]');
    const opener = gallery.querySelector('[data-gallery-open]');
    if (!lightbox || typeof lightbox.showModal !== 'function') { opener?.remove(); return; }
    const stage = lightbox.querySelector('[data-lightbox-stage]');
    const image = lightbox.querySelector('[data-lightbox-image]');
    const hint = lightbox.querySelector('[data-lightbox-count]');
    const lightPrev = lightbox.querySelector('[data-lightbox-prev]');
    const lightNext = lightbox.querySelector('[data-lightbox-next]');
    let shown = 0;
    const setZoom = (zoomed, event) => {
      stage.classList.toggle('is-zoomed', zoomed);
      if (zoomed && event) {
        // ซูมเข้าไปตรงจุดที่กด
        const box = image.getBoundingClientRect();
        const x = (event.clientX - box.left) / box.width; const y = (event.clientY - box.top) / box.height;
        stage.scrollLeft = x * stage.scrollWidth - stage.clientWidth / 2;
        stage.scrollTop = y * stage.scrollHeight - stage.clientHeight / 2;
      }
    };
    const show = (index) => {
      shown = Math.max(0, Math.min(total - 1, index));
      image.src = slides[shown].currentSrc || slides[shown].src;
      image.alt = slides[shown].alt;
      setZoom(false);
      if (lightPrev) lightPrev.disabled = shown <= 0;
      if (lightNext) lightNext.disabled = shown >= total - 1;
      hint.textContent = (total > 1 ? `${shown + 1} / ${total} · ` : '') + 'กดที่รูปเพื่อซูม';
    };
    const open = (index) => { show(index); lightbox.showModal(); };
    opener?.addEventListener('click', () => open(current()));
    slides.forEach((slide, index) => slide.addEventListener('click', () => open(index)));
    image.addEventListener('click', (event) => setZoom(!stage.classList.contains('is-zoomed'), event));
    lightPrev?.addEventListener('click', () => show(shown - 1));
    lightNext?.addEventListener('click', () => show(shown + 1));
    lightbox.querySelector('[data-lightbox-close]').addEventListener('click', () => lightbox.close());
    // กดพื้นที่มืดรอบรูปเพื่อปิด
    stage.addEventListener('click', (event) => { if (event.target === stage) lightbox.close(); });
    lightbox.addEventListener('keydown', (event) => {
      if (event.key === 'ArrowLeft') { event.preventDefault(); show(shown - 1); }
      if (event.key === 'ArrowRight') { event.preventDefault(); show(shown + 1); }
    });
    lightbox.addEventListener('close', () => { go(shown); opener?.focus(); });
  });
});
