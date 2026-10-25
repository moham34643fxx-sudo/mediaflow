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
  document.addEventListener('DOMContentLoaded', () => {
    updateDOMTranslations();
    updateScreenshotView(0);
  });

})();
