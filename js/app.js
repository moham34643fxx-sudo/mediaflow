/**
 * MediaFlow Official Website - Interactive Engine
 * Handles bilingual switching (AR/EN), interactive screenshot showcase,
 * modals & accordion.
 */

(function () {
  'use strict';

  // --- State ---
  let currentLang = localStorage.getItem('mediaflow_lang') || 'ar';

  // Screenshot items definition (4 authentic screens)
  const screenshotData = [
    {
      id: 'home',
      img: 'assets/screenshots/home.png',
      captionKey: 'homeCaption',
      badgeKey: 'homeBadge'
    },
    {
      id: 'downloads',
      img: 'assets/screenshots/downloads.png',
      captionKey: 'downloadsCaption',
      badgeKey: 'downloadsBadge'
    },
    {
      id: 'library',
      img: 'assets/screenshots/library.png',
      captionKey: 'libraryCaption',
      badgeKey: 'libraryBadge'
    },
    {
      id: 'player',
      img: 'assets/screenshots/player.png',
      captionKey: 'playerCaption',
      badgeKey: 'playerBadge'
    }
  ];

  let activeScreenshotIndex = 0;

  // --- Helper to resolve nested keys like "hero.subtitle" ---
  function getNestedTranslation(obj, path) {
    return path.split('.').reduce((prev, curr) => (prev ? prev[curr] : undefined), obj);
  }

  // --- Update all i18n DOM elements ---
  function updateDOMTranslations() {
    const langData = translations[currentLang];
    if (!langData) return;

    // Document attributes
    document.documentElement.lang = currentLang;
    document.documentElement.dir = currentLang === 'ar' ? 'rtl' : 'ltr';

    // Update <title> and <meta> tags
    if (langData.meta) {
      if (langData.meta.title) {
        document.title = langData.meta.title;
      }
      if (langData.meta.desc) {
        const metaDesc = document.querySelector('meta[name="description"]');
        if (metaDesc) metaDesc.setAttribute('content', langData.meta.desc);
        const ogDesc = document.querySelector('meta[property="og:description"]');
        if (ogDesc) ogDesc.setAttribute('content', langData.meta.desc);
      }
      if (langData.meta.title) {
        const ogTitle = document.querySelector('meta[property="og:title"]');
        if (ogTitle) ogTitle.setAttribute('content', langData.meta.title);
      }
    }

    // Text content
    document.querySelectorAll('[data-i18n]').forEach((el) => {
      const key = el.getAttribute('data-i18n');
      const val = getNestedTranslation(langData, key);
      if (val !== undefined) {
        // If it contains newlines, preserve them
        if (typeof val === 'string' && val.includes('\n')) {
          el.innerHTML = val.replace(/\n/g, '<br/>');
        } else {
          el.textContent = val;
        }
      }
    });

    // Attributes (like title or placeholder)
    document.querySelectorAll('[data-i18n-attr]').forEach((el) => {
      const mapping = el.getAttribute('data-i18n-attr').split(':');
      if (mapping.length === 2) {
        const attr = mapping[0];
        const key = mapping[1];
        const val = getNestedTranslation(langData, key);
        if (val !== undefined) {
          el.setAttribute(attr, val);
        }
      }
    });

    // Update screenshot caption & badge
    updateScreenshotView(activeScreenshotIndex);

    // Save preference
    localStorage.setItem('mediaflow_lang', currentLang);
    window.MediaFlowReleases?.render(document, currentLang);
  }

  // --- Toggle Language ---
  function toggleLanguage() {
    currentLang = currentLang === 'ar' ? 'en' : 'ar';
    updateDOMTranslations();
  }



  // --- Mobile Navigation Menu ---
  const mobileMenuBtn = document.getElementById('mobile-menu-btn');
  const mobileMenu = document.getElementById('mobile-menu');

  if (mobileMenuBtn && mobileMenu) {
    mobileMenuBtn.addEventListener('click', () => {
      const isHidden = mobileMenu.classList.contains('hidden');
      if (isHidden) {
        mobileMenu.classList.remove('hidden');
      } else {
        mobileMenu.classList.add('hidden');
      }
    });

    // Close menu when clicking any nav link
    mobileMenu.querySelectorAll('a').forEach((link) => {
      link.addEventListener('click', () => {
        mobileMenu.classList.add('hidden');
      });
    });
  }



  // --- Screenshots Showcase ---
  const screenshotImg = document.getElementById('screenshot-display-img');
  const screenshotCaption = document.getElementById('screenshot-caption');
  const screenshotBadge = document.getElementById('screenshot-badge');
  const tabButtons = document.querySelectorAll('.screenshot-tab-btn');

  function updateScreenshotView(index) {
    activeScreenshotIndex = index;
    const item = screenshotData[index];
    if (!item) return;

    if (screenshotImg) {
      screenshotImg.style.opacity = '0';
      setTimeout(() => {
        screenshotImg.src = item.img;
        screenshotImg.style.opacity = '1';
      }, 150);
    }

    if (screenshotCaption) {
      const captionText = getNestedTranslation(translations[currentLang], `screenshots.${item.captionKey}`);
      screenshotCaption.textContent = captionText || '';
    }

    if (screenshotBadge) {
      const badgeText = getNestedTranslation(translations[currentLang], `screenshots.${item.badgeKey}`);
      screenshotBadge.textContent = badgeText || '';
    }

    tabButtons.forEach((btn, i) => {
      if (i === index) {
        btn.classList.add('active');
      } else {
        btn.classList.remove('active');
      }
    });
  }

  tabButtons.forEach((btn, idx) => {
    btn.addEventListener('click', () => {
      updateScreenshotView(idx);
    });
  });

  // --- Lightbox Modal for Screenshots ---
  const lightboxModal = document.getElementById('lightbox-modal');
  const lightboxImg = document.getElementById('lightbox-img');
  const lightboxCloseBtn = document.getElementById('lightbox-close-btn');

  if (screenshotImg && lightboxModal && lightboxImg) {
    screenshotImg.addEventListener('click', () => {
      lightboxImg.src = screenshotImg.src;
      lightboxModal.classList.add('active');
      document.body.style.overflow = 'hidden';
    });
  }

  if (lightboxCloseBtn) {
    lightboxCloseBtn.addEventListener('click', () => {
      lightboxModal.classList.remove('active');
      document.body.style.overflow = '';
    });
  }

  if (lightboxModal) {
    lightboxModal.addEventListener('click', (e) => {
      if (e.target === lightboxModal) {
        lightboxModal.classList.remove('active');
        document.body.style.overflow = '';
      }
    });
  }

  // --- Accordion FAQ ---
  const faqItems = document.querySelectorAll('.faq-item');
  faqItems.forEach((item) => {
    const header = item.querySelector('.faq-header');
    if (header) {
      header.addEventListener('click', () => {
        const isOpen = item.classList.contains('open');
        // Close others
        faqItems.forEach((other) => other.classList.remove('open'));
        if (!isOpen) {
          item.classList.add('open');
        }
      });
    }
  });

  // Open first FAQ by default
  if (faqItems.length > 0) {
    faqItems[0].classList.add('open');
  }

  // --- Back to Top Button ---
  const backToTopBtn = document.getElementById('back-to-top');
  if (backToTopBtn) {
    window.addEventListener('scroll', () => {
      if (window.scrollY > 400) {
        backToTopBtn.classList.add('visible');
      } else {
        backToTopBtn.classList.remove('visible');
      }
    });

    backToTopBtn.addEventListener('click', () => {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  // --- Keyboard Shortcuts (Escape closes modals) ---
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (lightboxModal) {
        lightboxModal.classList.remove('active');
        document.body.style.overflow = '';
      }
    }
  });

  // --- Language toggle buttons ---
  const langToggleBtns = document.querySelectorAll('.lang-toggle-btn');
  langToggleBtns.forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      toggleLanguage();
    });
  });

  // --- Initialize on DOM ready ---

  // --- Universal Bulletproof Download Manager ---
  function initDownloadManager() {
    const directServerUrl = 'downloads/MediaFlow-latest.apk';
    const githubCdnUrl = 'https://github.com/moham34643fxx-sudo/mediaflow/releases/download/qa-0.9.28/MediaFlow-0.9.28-arm64-v8a.apk';

    // 1. Detect platform/device
    const ua = navigator.userAgent || '';
    const isIOS = /iPhone|iPad|iPod/i.test(ua);
    const isWindows = /Windows/i.test(ua);
    const isMac = /Macintosh|Mac OS/i.test(ua) && !isIOS;
    const isInAppBrowser = /FBAN|FBAV|Instagram|TikTok|Line|Twitter|Snapchat/i.test(ua);

    // 2. Create or obtain Toast element
    let toast = document.getElementById('mediaflow-download-toast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'mediaflow-download-toast';
      toast.className = 'download-toast';
      toast.setAttribute('role', 'alert');
      toast.setAttribute('aria-live', 'assertive');
      document.body.appendChild(toast);
    }

    let toastTimer = null;

    function renderToast(lang) {
      const t = translations[lang]?.downloadToast || translations.ar.downloadToast;
      let platformTip = t.androidTip;
      if (isIOS) platformTip = t.iosTip;
      else if (isWindows || isMac) platformTip = t.pcTip;

      let inAppWarning = '';
      if (isInAppBrowser) {
        inAppWarning = lang === 'ar'
          ? '<div class="download-toast-tip" style="background:rgba(239,68,68,0.15);border-color:rgba(239,68,68,0.3);color:#FCA5A5;margin-bottom:8px;">⚠️ يرجى فتح الصفحة في متصفح خارجي (Chrome أو Samsung Internet) إذا منع متصفح التطبيق الحالي حفظ الملف.</div>'
          : '<div class="download-toast-tip" style="background:rgba(239,68,68,0.15);border-color:rgba(239,68,68,0.3);color:#FCA5A5;margin-bottom:8px;">⚠️ Please open this page in external Chrome or Safari if this in-app browser blocks file saving.</div>';
      }

      toast.innerHTML = `
        <div class="download-toast-header">
          <div class="download-toast-icon">
            <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/>
            </svg>
          </div>
          <div class="download-toast-content">
            <div class="download-toast-title">
              <span>${t.started}</span>
              <span class="inline-block w-2.5 h-2.5 rounded-full bg-emerald-400 animate-ping"></span>
            </div>
            <div class="download-toast-desc">${t.startedDesc}</div>
            ${inAppWarning}
            <div class="download-toast-tip">${platformTip}</div>
            <div class="download-toast-actions">
              <a href="${directServerUrl}" download="MediaFlow-latest.apk" class="download-toast-btn download-toast-btn-primary">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"/>
                </svg>
                <span>${t.directVercel}</span>
              </a>
              <a href="${githubCdnUrl}" target="_blank" rel="noopener noreferrer" class="download-toast-btn download-toast-btn-secondary">
                <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
                  <path fill-rule="evenodd" clip-rule="evenodd" d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.53 1.032 1.53 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z"/>
                </svg>
                <span>${t.directGithub}</span>
              </a>
            </div>
          </div>
          <button type="button" class="download-toast-close" aria-label="Close" id="download-toast-close-btn">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
            </svg>
          </button>
        </div>
      `;

      const closeBtn = document.getElementById('download-toast-close-btn');
      if (closeBtn) {
        closeBtn.onclick = () => {
          toast.classList.remove('show');
          if (toastTimer) clearTimeout(toastTimer);
        };
      }
    }

    function showToast() {
      renderToast(currentLang);
      toast.classList.add('show');
      if (toastTimer) clearTimeout(toastTimer);
      toastTimer = setTimeout(() => {
        toast.classList.remove('show');
      }, 16000);
    }

    // 3. Fallback direct download trigger
    function triggerDirectDownload(url) {
      try {
        const tempLink = document.createElement('a');
        tempLink.style.display = 'none';
        tempLink.href = url;
        tempLink.setAttribute('download', 'MediaFlow-latest.apk');
        document.body.appendChild(tempLink);
        tempLink.click();
        setTimeout(() => {
          if (tempLink.parentNode) tempLink.parentNode.removeChild(tempLink);
        }, 1000);
      } catch (err) {
        console.warn('Fallback link trigger failed:', err);
      }
    }

    // 4. Attach to all download buttons
    const downloadSelectors = [
      'a[href*="downloads/MediaFlow"]',
      'a[download]',
      '.btn-primary[href*="apk"]',
      'a[href*="releases/download"]'
    ];

    const buttons = document.querySelectorAll(downloadSelectors.join(','));

    buttons.forEach((btn) => {
      // Ensure target URL is always the same-origin direct file
      btn.setAttribute('href', directServerUrl);
      btn.setAttribute('download', 'MediaFlow-latest.apk');

      btn.addEventListener('click', function (e) {
        // Non-destructive visual feedback: do not destroy DOM nodes during event dispatch
        this.classList.add('download-btn-loading');
        setTimeout(() => {
          this.classList.remove('download-btn-loading');
        }, 3500);

        // Show the interactive toast
        showToast();
      });
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    window.MediaFlowReleases?.start(document, translations);
    updateDOMTranslations();
    updateScreenshotView(0);
    initDownloadManager();
  });

})();
