(function () {
  var B = window.AndroidStore;
  var last = '';
  window.dodo = {
    openWA: function () { B.openWA(); return Promise.resolve(); },
    saveCsv: function (t) { B.saveCsv(t); },
    storage: {
      local: {
        get: function (k) { return Promise.resolve(JSON.parse(B.get(JSON.stringify(k == null ? null : k)))); },
        set: function (o) { B.set(JSON.stringify(o)); return Promise.resolve(); },
        remove: function (k) { B.remove(JSON.stringify(k)); return Promise.resolve(); }
      },
      onChanged: {
        addListener: function (fn) {
          setInterval(function () {
            var s = B.get('["state"]');
            if (s !== last) { last = s; var st = JSON.parse(s).state; if (st) fn({ state: { newValue: st } }); }
          }, 800);
        }
      }
    }
  };
})();
