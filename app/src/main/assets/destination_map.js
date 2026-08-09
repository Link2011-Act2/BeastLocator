(function () {
    "use strict";

    var ERROR_PREFIX = "BEASTLOCATOR_MAP_ERROR";

    function numberParameter(parameters, name, fallback) {
        var value = Number(parameters.get(name));
        return Number.isFinite(value) ? value : fallback;
    }

    function reportPosition(prefix, map) {
        var center = map.getCenter();
        console.log(prefix + "|" + center.lat + "|" + center.lng + "|" + map.getZoom());
    }

    try {
        if (typeof L === "undefined") {
            throw new Error("Leaflet is unavailable");
        }

        var parameters = new URLSearchParams(window.location.search);
        var latitude = numberParameter(parameters, "lat", 35.665554);
        var longitude = numberParameter(parameters, "lng", 139.669717);
        var zoom = numberParameter(parameters, "zoom", 15);
        var map = L.map("map", {
            attributionControl: false,
            zoomControl: true,
            preferCanvas: true
        });

        L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
            minZoom: 0,
            maxZoom: 19,
            tileSize: 256,
            updateWhenIdle: true,
            keepBuffer: 0
        }).addTo(map);

        map.on("movestart", function () {
            console.log("BEASTLOCATOR_MAP_MOVE_START");
        });
        map.on("moveend", function () {
            reportPosition("BEASTLOCATOR_MAP_MOVE_END", map);
        });

        window.BeastLocatorMap = {
            moveTo: function (lat, lng, nextZoom) {
                if (!Number.isFinite(lat) || !Number.isFinite(lng) || !Number.isFinite(nextZoom)) {
                    return;
                }
                map.flyTo([lat, lng], nextZoom, { animate: true, duration: 0.8 });
            },
            getCenter: function () {
                var center = map.getCenter();
                return [center.lat, center.lng, map.getZoom()];
            }
        };

        map.setView([latitude, longitude], zoom, { animate: false });
        window.requestAnimationFrame(function () {
            map.invalidateSize(false);
            reportPosition("BEASTLOCATOR_MAP_READY", map);
        });
    } catch (error) {
        console.log(ERROR_PREFIX + "|" + String(error && error.message ? error.message : error));
    }
}());
