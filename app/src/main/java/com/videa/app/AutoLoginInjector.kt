package com.videa.app

import android.content.Context
import android.util.Log
import android.webkit.WebView

/**
 * Auto-login injector untuk portal sekolah 2 tahap (Gateway -> Final Form).
 *
 * Cara pakai:
 *   AutoLoginInjector.inject(this, webView)
 * Panggil di dalam WebViewClient.onPageFinished(view, url) setelah halaman selesai load.
 *
 * Pantau prosesnya lewat Logcat filter tag/pesan "AutoLoginJS:" via
 * WebChromeClient.onConsoleMessage(...).
 *
 * DETEKSI LOGOUT:
 * Kalau sesi ini sebelumnya SUDAH pernah berhasil auto-login (hasAuthenticatedOnce = true),
 * lalu tiba-tiba muncul form login lagi (Tahap 1 / Tahap 2), itu dianggap sebagai LOGOUT
 * yang dipicu dari website -- bukan kondisi awal buka app. Script TIDAK akan login ulang,
 * melainkan memanggil AndroidInterface.requestManualLogout() supaya app pindah ke
 * LoginActivity native (bukan diam di WebView, apalagi auto-login lagi).
 */
object AutoLoginInjector {

    private const val MAX_TICKS = 30 // ~30 detik polling sebelum menyerah

    @Volatile
    private var hasAuthenticatedOnce = false

    /** Dipanggil dari WebAppInterface.onAutoLoginSuccess() setelah Tahap 2 berhasil diklik. */
    @JvmStatic
    fun markAuthenticated() {
        hasAuthenticatedOnce = true
        Log.d("AutoLoginJS", "Kotlin: sesi ditandai authenticated")
    }

    /** Dipanggil dari WebAppInterface.requestManualLogout() supaya sesi berikutnya auto-login normal lagi. */
    @JvmStatic
    fun resetSession() {
        hasAuthenticatedOnce = false
        Log.d("AutoLoginJS", "Kotlin: sesi direset (logout)")
    }

    @JvmStatic
    fun inject(context: Context, view: WebView?) {
        val webView = view ?: return

        val username = PreferenceHelper.getRegisteredNumber(context)
        val password = PreferenceHelper.getPassword(context)
        val role = PreferenceHelper.getRole(context) ?: "Siswa"

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Log.d("AutoLoginJS", "Kotlin: username/password kosong di SharedPreferences, batal inject")
            return
        }

        val jsUsername = escapeForJs(username)
        val jsPassword = escapeForJs(password)
        val jsRole = escapeForJs(role)
        val alreadyAuthenticatedLiteral = if (hasAuthenticatedOnce) "true" else "false"

        val js = """
        (function() {
            if (window.__autoLoginActive) {
                console.log('AutoLoginJS: sudah aktif di halaman ini, skip injeksi ulang');
                return;
            }
            window.__autoLoginActive = true;
            window.__autoLoginDone = false;

            var USERNAME = '$jsUsername';
            var PASSWORD = '$jsPassword';
            var ROLE = '$jsRole';
            var ALREADY_AUTHENTICATED = $alreadyAuthenticatedLiteral;
            var MAX_TICKS = $MAX_TICKS;
            var tickCount = 0;

            console.log('AutoLoginJS: interval dimulai, role target = ' + ROLE + ', alreadyAuthenticated = ' + ALREADY_AUTHENTICATED);

            var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
            var nativeSelectValueSetter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value')
                ? Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value').set
                : null;

            function isVisible(el) {
                if (!el) return false;
                var rect = el.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0) return false;
                var style = window.getComputedStyle(el);
                if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) === 0) return false;
                return true;
            }

            function isInteractive(el) {
                if (!el) return false;
                var tag = el.tagName ? el.tagName.toLowerCase() : '';
                if (tag === 'button' || tag === 'a') return true;
                if (tag === 'input' && (el.type === 'submit' || el.type === 'button')) return true;
                var style = window.getComputedStyle(el);
                if (style.cursor === 'pointer') return true;
                return false;
            }

            function setNativeValue(el, value) {
                if (!el) return;
                try {
                    if (el.tagName && el.tagName.toLowerCase() === 'select' && nativeSelectValueSetter) {
                        nativeSelectValueSetter.call(el, value);
                    } else {
                        nativeInputValueSetter.call(el, value);
                    }
                } catch (e) {
                    el.value = value;
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                el.dispatchEvent(new Event('blur', { bubbles: true }));
            }

            function findInputByPattern(pattern) {
                var inputs = document.querySelectorAll('input');
                for (var i = 0; i < inputs.length; i++) {
                    var el = inputs[i];
                    if (!isVisible(el)) continue;
                    var attrs = [el.name, el.id, el.placeholder, el.getAttribute('formcontrolname')].join(' ').toLowerCase();
                    if (pattern.test(attrs)) return el;
                }
                return null;
            }

            function findPasswordInput() {
                var inputs = document.querySelectorAll('input[type="password"]');
                for (var i = 0; i < inputs.length; i++) {
                    if (isVisible(inputs[i])) return inputs[i];
                }
                return findInputByPattern(/pass/);
            }

            function findClickableByExactText(exactText) {
                var all = document.querySelectorAll('button, a, input[type="submit"], input[type="button"], [role="button"], div, span');
                var target = exactText.trim().toLowerCase();
                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    var text = (el.textContent || el.value || '').trim().toLowerCase();
                    if (text === target && isVisible(el)) {
                        return el;
                    }
                }
                return null;
            }

            function findClickableByContainsAndInteractive(containsText) {
                var all = document.querySelectorAll('button, a, input[type="submit"], input[type="button"], [role="button"]');
                var target = containsText.toLowerCase();
                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    var text = (el.textContent || el.value || '').trim().toLowerCase();
                    if (text.indexOf(target) !== -1 && isVisible(el) && isInteractive(el)) {
                        return el;
                    }
                }
                return null;
            }

            function clickElement(el, label) {
                if (!el) return false;
                console.log('AutoLoginJS: klik -> ' + label);
                el.click();
                return true;
            }

            function fillUsernamePassword() {
                var userEl = findInputByPattern(/user|nisn|nip|hp|no[_\s]?induk|no[_\s]?hp|username/);
                var passEl = findPasswordInput();
                if (userEl) {
                    setNativeValue(userEl, USERNAME);
                    console.log('AutoLoginJS: username diisi');
                } else {
                    console.log('AutoLoginJS: input username tidak ditemukan');
                }
                if (passEl) {
                    setNativeValue(passEl, PASSWORD);
                    console.log('AutoLoginJS: password diisi');
                } else {
                    console.log('AutoLoginJS: input password tidak ditemukan');
                }
                return !!(userEl && passEl);
            }

            function selectRole() {
                var roleEl = findClickableByExactText(ROLE);
                if (roleEl && isInteractive(roleEl)) {
                    clickElement(roleEl, 'Role: ' + ROLE);
                    return true;
                }
                var selects = document.querySelectorAll('select');
                for (var i = 0; i < selects.length; i++) {
                    var sel = selects[i];
                    if (!isVisible(sel)) continue;
                    var options = sel.querySelectorAll('option');
                    for (var j = 0; j < options.length; j++) {
                        if (options[j].textContent.trim().toLowerCase() === ROLE.toLowerCase()) {
                            setNativeValue(sel, options[j].value);
                            console.log('AutoLoginJS: role dipilih via <select>: ' + ROLE);
                            return true;
                        }
                    }
                }
                console.log('AutoLoginJS: elemen role "' + ROLE + '" tidak ditemukan/tidak interaktif');
                return false;
            }

            function stopAndCallNativeLogout() {
                clearInterval(timer);
                window.__autoLoginActive = false;
                console.log('AutoLoginJS: form login terdeteksi padahal sesi sebelumnya sudah authenticated -> LOGOUT terdeteksi, panggil native logout');
                if (window.AndroidInterface && window.AndroidInterface.requestManualLogout) {
                    window.AndroidInterface.requestManualLogout();
                } else {
                    console.log('AutoLoginJS: AndroidInterface.requestManualLogout tidak tersedia');
                }
            }

            function notifyNativeLoginSuccess() {
                if (window.AndroidInterface && window.AndroidInterface.onAutoLoginSuccess) {
                    window.AndroidInterface.onAutoLoginSuccess();
                }
            }

            function tick() {
                if (window.__autoLoginDone) {
                    clearInterval(timer);
                    return;
                }

                tickCount++;
                if (tickCount > MAX_TICKS) {
                    console.log('AutoLoginJS: timeout, berhenti memantau DOM');
                    clearInterval(timer);
                    window.__autoLoginActive = false;
                    return;
                }

                console.log('AutoLoginJS: tick #' + tickCount);

                var btnMasuk = findClickableByExactText('Masuk');
                var isTahap2 = btnMasuk && isInteractive(btnMasuk);

                var btnLanjut = findClickableByContainsAndInteractive('lanjutkan login')
                    || findClickableByContainsAndInteractive('masuk ke sistem');
                var isTahap1 = !!btnLanjut;

                // Deteksi LOGOUT: form login muncul lagi padahal sesi ini sebelumnya sudah authenticated.
                if ((isTahap1 || isTahap2) && ALREADY_AUTHENTICATED) {
                    stopAndCallNativeLogout();
                    return;
                }

                // PRIORITAS 1: Tombol "Masuk" (Tahap 2 - Final Form)
                if (isTahap2) {
                    console.log('AutoLoginJS: terdeteksi Tahap 2 (form final)');
                    selectRole();
                    fillUsernamePassword();
                    setTimeout(function() {
                        var finalBtn = findClickableByExactText('Masuk');
                        if (finalBtn && isInteractive(finalBtn) && isVisible(finalBtn)) {
                            clickElement(finalBtn, 'Masuk (Tahap 2 - Final)');
                            window.__autoLoginDone = true;
                            notifyNativeLoginSuccess();
                        } else {
                            console.log('AutoLoginJS: tombol Masuk hilang sesaat sebelum klik, coba lagi tick berikutnya');
                        }
                    }, 300);
                    return;
                }

                // PRIORITAS 2: Tombol Tahap 1 (Gateway)
                if (isTahap1) {
                    console.log('AutoLoginJS: terdeteksi Tahap 1 (gateway)');
                    var filled = fillUsernamePassword();
                    if (filled) {
                        setTimeout(function() {
                            var b = findClickableByContainsAndInteractive('lanjutkan login')
                                || findClickableByContainsAndInteractive('masuk ke sistem');
                            if (b && isVisible(b)) {
                                clickElement(b, 'Lanjutkan Login (Tahap 1)');
                            }
                        }, 300);
                    }
                } else {
                    console.log('AutoLoginJS: belum ada tombol tahap 1 maupun tahap 2 yang terdeteksi');
                }
            }

            var timer = setInterval(tick, 1000);
            tick();
        })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun escapeForJs(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }
}