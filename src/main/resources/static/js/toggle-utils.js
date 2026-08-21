/* toggle-utils.js — shared toggle helpers for checkbox switches and button toggles */
/* jshint esversion: 6 */

'use strict';

(function (global) {
    function resolveControl(controlOrId) {
        if (!controlOrId) {
            return null;
        }
        if (typeof controlOrId === 'string') {
            return document.getElementById(controlOrId);
        }
        return controlOrId;
    }

    function isCheckbox(control) {
        return !!(control && control.matches && control.matches('input[type="checkbox"]'));
    }

    function getState(controlOrId) {
        var control = resolveControl(controlOrId);
        if (!control) {
            return false;
        }
        if (isCheckbox(control)) {
            return !!control.checked;
        }
        return control.getAttribute('aria-pressed') === 'true';
    }

    function setState(controlOrId, enabled) {
        var control = resolveControl(controlOrId);
        if (!control) {
            return;
        }
        if (isCheckbox(control)) {
            control.checked = !!enabled;
            return;
        }
        control.setAttribute('aria-pressed', enabled ? 'true' : 'false');
        control.textContent = enabled ? 'On' : 'Off';
    }

    function bind(controlOrId, onChange) {
        var control = resolveControl(controlOrId);
        if (!control) {
            return;
        }

        if (isCheckbox(control)) {
            control.addEventListener('change', function () {
                if (typeof onChange === 'function') {
                    onChange(!!control.checked, control);
                }
            });
        } else {
            control.addEventListener('click', function () {
                var next = !getState(control);
                setState(control, next);
                if (typeof onChange === 'function') {
                    onChange(next, control);
                }
            });
        }

        setState(control, getState(control));
    }

    global.VorkToggleUtil = {
        bind: bind,
        setState: setState,
        getState: getState
    };
}(window));
