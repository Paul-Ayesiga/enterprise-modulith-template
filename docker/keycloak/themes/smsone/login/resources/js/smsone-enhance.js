/*
 * SMSOne login-theme enhancements (progressive — pure DOM, no deps):
 *   1. Show/hide (eye) toggle on every password field.
 *   2. Loading spinner on the primary submit button while the form posts.
 *
 * Submit inputs are upgraded to <button> so the spinner can render via ::after
 * (replaced <input> elements can't carry pseudo-elements). The busy state is
 * applied on a 0ms timeout so the clicked button's name/value is still included
 * in the POST before it's disabled.
 */
(function () {
  'use strict';

  var EYE =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>';
  var EYE_OFF =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 3l18 18"/><path d="M10.6 10.6a3 3 0 0 0 4.2 4.2"/><path d="M9.88 5.09A10.4 10.4 0 0 1 12 5c6.5 0 10 7 10 7a17.8 17.8 0 0 1-3.06 3.94"/><path d="M6.1 6.1A17.85 17.85 0 0 0 2 12s3.5 7 10 7a10.4 10.4 0 0 0 3.9-.74"/></svg>';

  function each(list, fn) {
    Array.prototype.forEach.call(list, fn);
  }

  function addPasswordToggles() {
    each(document.querySelectorAll('input[type="password"]'), function (input) {
      if (input.getAttribute('data-cz-eye')) return;
      var wrap = input.closest ? input.closest('.smsone-input-wrapper') : null;
      if (!wrap) return;
      // Don't double up where the page already ships a manual toggle.
      if (wrap.querySelector('.smsone-password-toggle, .smsone-pw-toggle')) return;
      input.setAttribute('data-cz-eye', '1');
      input.classList.add('smsone-input--has-toggle');

      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'smsone-pw-toggle';
      btn.tabIndex = -1;
      btn.setAttribute('aria-label', 'Show password');
      btn.innerHTML = EYE;
      btn.addEventListener('click', function () {
        var reveal = input.type === 'password';
        input.type = reveal ? 'text' : 'password';
        btn.innerHTML = reveal ? EYE_OFF : EYE;
        btn.setAttribute('aria-label', reveal ? 'Hide password' : 'Show password');
        input.focus();
      });
      wrap.appendChild(btn);
    });
  }

  function upgradeSubmitButtons() {
    each(document.querySelectorAll('input.smsone-button-primary[type="submit"], input.smsone-button-ghost[type="submit"]'), function (input) {
      var btn = document.createElement('button');
      btn.type = 'submit';
      btn.className = input.className;
      if (input.name) btn.name = input.name;
      if (input.value) btn.value = input.value;
      if (input.id) btn.id = input.id;
      var tab = input.getAttribute('tabindex');
      if (tab !== null) btn.setAttribute('tabindex', tab);
      if (input.hasAttribute('disabled')) btn.disabled = true;

      var label = document.createElement('span');
      label.className = 'cz-btn-label';
      label.textContent = input.value || 'Continue';
      btn.appendChild(label);

      input.parentNode.replaceChild(btn, input);
    });
  }

  var SPINNABLE = ['smsone-button-primary', 'smsone-button-ghost', 'smsone-social-button'];

  function isSpinnable(el) {
    if (!el || !el.classList) return false;
    return SPINNABLE.some(function (c) {
      return el.classList.contains(c);
    });
  }

  function markBusy(btn) {
    if (!btn || btn.getAttribute('data-cz-busy')) return;
    btn.setAttribute('data-cz-busy', '1');
    btn.classList.add('is-loading');
    try {
      btn.disabled = true;
    } catch (_) {
      /* anchors have no disabled */
    }
  }

  function bindSpinners() {
    // Form submits: whichever styled button submitted spins (primary OR ghost
    // — "Try another way", resends, cancels all post forms too).
    each(document.querySelectorAll('form'), function (form) {
      form.addEventListener('submit', function (e) {
        var btn = e.submitter || form.querySelector('.smsone-button-primary');
        if (!isSpinnable(btn)) return;
        // Defer so the submitter's name/value is captured in the POST first.
        setTimeout(function () {
          markBusy(btn);
        }, 0);
      });
    });

    // WebAuthn triggers (type="button"): the click starts the browser's
    // passkey ceremony, which always ends in a hidden-form navigation
    // (success, error, or user-cancel) — busy-until-navigation is correct.
    each(document.querySelectorAll('button[type="button"].smsone-button-primary, button[type="button"].smsone-button-ghost'), function (btn) {
      btn.addEventListener('click', function () {
        // Skip pure-UI toggles (password eye etc. carry their own classes).
        markBusy(btn);
      });
    });

    // Identity-provider links (Google SSO et al.) navigate away — spin so the
    // handoff reads as progress, mirroring the portals' auth veil.
    each(document.querySelectorAll('a.smsone-social-button, #kc-social-providers a'), function (a) {
      a.addEventListener('click', function () {
        markBusy(a);
      });
    });
  }

  function init() {
    addPasswordToggles();
    upgradeSubmitButtons();
    bindSpinners();
  }

  if (document.readyState !== 'loading') init();
  else document.addEventListener('DOMContentLoaded', init);
})();
