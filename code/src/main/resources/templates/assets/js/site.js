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
    const prev = gallery.querySelector('[data-gallery-prev]');
    const next = gallery.querySelector('[data-gallery-next]');
    const count = gallery.querySelector('[data-gallery-count]');
    if (!track || !prev || !next) return;
    const total = track.children.length;
    const current = () => Math.round(track.scrollLeft / Math.max(track.clientWidth, 1));
    const go = (index) => track.scrollTo({left: Math.max(0, Math.min(total - 1, index)) * track.clientWidth});
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
  });
});
