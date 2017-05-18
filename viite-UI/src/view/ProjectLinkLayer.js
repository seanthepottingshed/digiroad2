(function(root) {
    root.ProjectLinkLayer = function(map, projectCollection) {
        var vectorLayer;
        var layerMinContentZoomLevels = {};
        var currentZoom = 0;
        var project;
        var styler = new Styler();

        var vectorSource = new ol.source.Vector({
            loader: function(extent, resolution, projection) {
                var zoom = Math.log(1024/resolution) / Math.log(2);
                    var features = _.map(projectCollection.getAll(), function(projectLink) {
                        var points = _.map(projectLink.points, function(point) {
                            return [point.x, point.y];
                        });
                        var feature =  new ol.Feature({ geometry: new ol.geom.LineString(points)
                        });
                        feature.projectLinkData = projectLink;
                        return feature;
                    });
                loadFeatures(features);
            },
            strategy: ol.loadingstrategy.bbox
        });

      var styleFunction = function (feature, resolution){

        if(feature.projectLinkData.status === 0) {
          var borderWidth = 3;
          var strokeWidth = styler.strokeWidthByZoomLevel(resolution, feature.projectLinkData.roadLinkType, feature.projectLinkData.anomaly, feature.projectLinkData.roadLinkSource, false, feature.projectLinkData.constructionType);
          var lineColor = 'rgba(247, 254, 46, 1)';
          var borderCap = 'round';

          var line = new ol.style.Stroke({
            width: strokeWidth + borderWidth,
            color: lineColor,
            lineCap: borderCap
          });

          //Declaration of the Line Styles
          var lineStyle = new ol.style.Style({
            stroke: line
          });

          var zIndex = styler.determineZIndex(feature.projectLinkData.roadLinkType, feature.projectLinkData.anomaly, feature.projectLinkData.roadLinkSource);
          lineStyle.setZIndex(zIndex + 2);
          return [lineStyle];
        }
        else{
          return styler.generateStyleByFeature(feature.projectLinkData, resolution);
        }
      };

        vectorLayer = new ol.layer.Vector({
            source: vectorSource,
            style: styleFunction
        });

        var loadFeatures = function (features) {
            vectorSource.addFeatures(features);
        };

        var show = function(map) {
            vectorLayer.setVisible(true);
        };

        var hideLayer = function() {
            this.stop();
            this.hide();
        };

        eventbus.on('roadAddressProject:openProject', function(projectSelected) {
          this.project = projectSelected;
          eventbus.trigger('roadAddressProject:selected', projectSelected.id);
        });

        eventbus.on('roadAddressProject:selected', function(projId) {
            console.log(projId);
          eventbus.once('roadAddressProject:projectFetched', function(id) {
            projectCollection.fetch(map.getView().calculateExtent(map.getSize()),map.getView().getZoom(), id);
            vectorSource.clear();
            eventbus.trigger('map:clearLayers');
            vectorLayer.changed();
          });
            projectCollection.getProjectsWithLinksById(projId);
        });

        eventbus.on('roadAddressProject:fetched', function(newRoads){
          var simulatedOL3Features = [];
          _.map(newRoads, function(road){
            var points = _.map(road[0].getData().points, function(point) {
              return [point.x, point.y];
            });
            var feature =  new ol.Feature({ geometry: new ol.geom.LineString(points)
            });
            feature.projectLinkData = road[0].getData();
            simulatedOL3Features.push(feature);
          });
          vectorLayer.getSource().addFeatures(simulatedOL3Features);
          vectorLayer.changed();
        });

        vectorLayer.setVisible(true);
        map.addLayer(vectorLayer);

        return {
            show: show,
            hide: hideLayer
        };
    };

})(this);