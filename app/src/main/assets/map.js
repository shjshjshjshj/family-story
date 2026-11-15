'use strict';
/* Leaflet 1.9.4 helper. Owns map setup, pins, and the saved road geometry.
   Only a server-provided LineString for the current trip/revision is drawn. */
(function (global) {
  const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  const TILE_ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors · <a href="https://www.openstreetmap.org/fixthemap" target="_blank" rel="noopener noreferrer">지도 오류 신고</a>';

  function point(value) {
    if (!value) return null;
    const rawLat = value.lat !== undefined ? value.lat : value.latitude;
    const rawLon = value.lon !== undefined ? value.lon : value.longitude;
    if (rawLat === null || rawLat === '' || rawLon === null || rawLon === '') return null;
    const lat = Number(rawLat), lon = Number(rawLon);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
    return [lat, lon];
  }

  function geometry(route, tripId, routeRevision) {
    if (!route || route.tripId !== tripId || route.routeRevision !== routeRevision) return null;
    const line = route.geometry;
    if (!line || line.type !== 'LineString' || !Array.isArray(line.coordinates) || line.coordinates.length < 2) return null;
    const out = [];
    for (const pair of line.coordinates) {
      if (!Array.isArray(pair) || pair.length !== 2) return null;
      const lon = Number(pair[0]), lat = Number(pair[1]);
      if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
      out.push([lat, lon]);
    }
    return out;
  }

  function create(container, options) {
    const opts = Object.assign({ onPick: null, onTileError: null }, options);
    let map = null, pins = null, line = null, candidateLayer = null, tileFailed = false;
    let pinSignature = '', lineSignature = '', fitSignature = '', candSignature = '';

    function init() {
      if (map) return true;
      if (typeof global.L === 'undefined') return false;
      map = global.L.map(container, { zoomControl: true });
      map.setView([36.4, 127.9], 6);
      global.L.tileLayer(TILE_URL, { maxZoom: 19, attribution: TILE_ATTRIBUTION })
        .on('tileerror', function () {
          if (tileFailed) return;
          tileFailed = true;
          if (opts.onTileError) opts.onTileError();
        })
        .addTo(map);
      map.on('click', function (event) {
        const target = event.originalEvent && event.originalEvent.target;
        if (target && target.closest && target.closest('.leaflet-marker-icon, .leaflet-popup')) return;
        if (opts.onPick) opts.onPick({ lat: event.latlng.lat, lon: event.latlng.lng });
      });
      pins = global.L.layerGroup().addTo(map);
      line = global.L.layerGroup().addTo(map);
      candidateLayer = global.L.layerGroup().addTo(map);
      return true;
    }

    function popup(place) {
      return function () {
        const box = document.createElement('div');
        const name = document.createElement('strong');
        name.textContent = place.name || '';
        box.append(name);
        if (place.final) {
          const final = document.createElement('p');
          final.textContent = '최종 목적지';
          box.append(final);
        }
        if (place.address) {
          const address = document.createElement('p');
          address.textContent = place.address;
          box.append(address);
        }
        return box;
      };
    }

    function pin(label, kind, place) {
      const coords = point(place);
      if (!coords) return null;
      const size = (global.document && global.document.body && global.document.body.classList.contains('vehicle')) ? 64 : 48;
      const icon = global.L.divIcon({
        className: 'cabin-pin ' + kind,
        html: '<span>' + label + '</span>',
        iconSize: [size, size],
        iconAnchor: [size / 2, size / 2],
        popupAnchor: [0, -Math.round(size / 2)]
      });
      const marker = global.L.marker(coords, { icon: icon, title: (place.name || '') + (place.final ? ' · 최종 목적지' : '') });
      marker.bindPopup(popup(place));
      return marker;
    }

    function render(data) {
      if (!init()) return false;
      const stops = (data.stops || []).map(function (stop) {
        const coords = point(stop);
        return coords ? Object.assign({}, stop, { lat: coords[0], lon: coords[1] }) : null;
      }).filter(Boolean);
      const originCoords = point(data.origin);
      const origin = originCoords ? Object.assign({}, data.origin, { lat: originCoords[0], lon: originCoords[1] }) : null;
      const nextPinSignature = JSON.stringify([
        origin && [origin.name, origin.lat, origin.lon],
        stops.map(function (stop) { return [stop.label, stop.name, stop.lat, stop.lon]; })
      ]);

      if (nextPinSignature !== pinSignature) {
        pinSignature = nextPinSignature;
        pins.clearLayers();
        if (origin) {
          const start = pin('출', 'origin', origin);
          if (start) pins.addLayer(start);
        }
        stops.forEach(function (stop) {
          const marker = pin(String(stop.label), 'stop', stop);
          if (marker) pins.addLayer(marker);
        });
      }

      const road = geometry(data.route, data.tripId, data.routeRevision);
      const nextLineSignature = road ? JSON.stringify([data.tripId || '', data.routeRevision || 0, road]) : '';
      if (nextLineSignature !== lineSignature) {
        lineSignature = nextLineSignature;
        line.clearLayers();
        if (road) line.addLayer(global.L.polyline(road, { color: '#245AE8', weight: 5, opacity: 0.8 }));
      }

      const candCoords = point(data.candidate);
      const nextCandSignature = candCoords ? JSON.stringify([candCoords[0], candCoords[1], data.candidate.name || '']) : '';
      if (nextCandSignature !== candSignature) {
        candSignature = nextCandSignature;
        candidateLayer.clearLayers();
        if (candCoords) {
          const candMarker = pin('?', 'candidate', {
            name: data.candidate.name || '선택한 지점',
            lat: candCoords[0],
            lon: candCoords[1],
            address: '위도 ' + candCoords[0].toFixed(5) + ' · 경도 ' + candCoords[1].toFixed(5)
          });
          if (candMarker) candidateLayer.addLayer(candMarker);
        }
      }

      const nextFitSignature = JSON.stringify([data.tripId || '', nextPinSignature, nextLineSignature]);
      if (data.fit !== false && nextFitSignature !== fitSignature) {
        const bounds = global.L.latLngBounds([]);
        if (origin) bounds.extend([origin.lat, origin.lon]);
        if (road) road.forEach(function (pair) { bounds.extend(pair); });
        stops.forEach(function (stop) { bounds.extend([stop.lat, stop.lon]); });
        if (bounds.isValid()) {
          fitSignature = nextFitSignature;
          map.fitBounds(bounds, { padding: [30, 30], maxZoom: 16 });
        }
      }
      return true;
    }

    return {
      render: render,
      invalidate: function () { if (map) map.invalidateSize(); },
      destroy: function () { if (map) { map.remove(); map = null; } }
    };
  }

  global.CabinMap = { create: create, available: function () { return typeof global.L !== 'undefined'; } };
})(window);
