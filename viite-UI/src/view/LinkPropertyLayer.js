(function(root) {
  root.LinkPropertyLayer = function(map, roadLayer, selectedLinkProperty, roadCollection, linkPropertiesModel, applicationModel) {
    var layerName = 'linkProperty';
    var cachedLinkPropertyMarker = null;
    var cachedMarker = null;
    Layer.call(this, layerName, roadLayer);
    var me = this;
    var eventListener = _.extend({running: false}, eventbus);
    var zoom = 0;
    var currentRenderIntent = 'default';
    var linkPropertyLayerStyles = LinkPropertyLayerStyles(roadLayer);
    this.minZoomForContent = zoomlevels.minZoomForRoadLinks;
    var indicatorLayer = new OpenLayers.Layer.Boxes('adjacentLinkIndicators');
    var floatingMarkerLayer = new OpenLayers.Layer.Boxes(layerName);
    var anomalousMarkerLayer = new OpenLayers.Layer.Boxes(layerName);
    map.addLayer(floatingMarkerLayer);
    map.addLayer(anomalousMarkerLayer);
    map.addLayer(indicatorLayer);
    floatingMarkerLayer.setVisibility(true);
    anomalousMarkerLayer.setVisibility(true);
    indicatorLayer.setVisibility(true);

    roadLayer.setLayerSpecificStyleMapProvider(layerName, function() {
      return linkPropertyLayerStyles.getDatasetSpecificStyleMap(linkPropertiesModel.getDataset(), currentRenderIntent);
    });

    var selectRoadLink = function(feature) {
      if(!applicationModel.isReadOnly() && feature.attributes.anomaly !== 1 && feature.attributes.roadLinkType !== -1){
          unhighlightFeatureByLinkId(feature.attributes.linkId);
      }
      else if(typeof feature.attributes.linkId !== 'undefined' && !applicationModel.isActiveButtons()) {
        if (selectedLinkProperty.getFeaturesToKeep().length === 0) {
          selectedLinkProperty.open(feature.attributes.linkId, feature.attributes.id, _.isUndefined(feature.singleLinkSelect) ? true : feature.singleLinkSelect);
        } else {
          selectedLinkProperty.open(feature.attributes.linkId, feature.attributes.id, true);
        }
        unhighlightFeatures();
        currentRenderIntent = 'select';
        roadLayer.redraw();
        if(!applicationModel.isReadOnly() && feature.attributes.roadLinkType == -1) {
          highlightAnomalousFeaturesByFloating();
        } else {
          highlightFeatures();
        }
        if(selectedLinkProperty.getFeaturesToKeep().length > 1){
          var floatingMinusLast = _.initial(selectedLinkProperty.getFeaturesToKeep());
          floatingMinusLast.forEach(function (fml){
            highlightFeatureByLinkId(fml.linkId);
            
            var floatingFeatures =[];
                _.each(roadLayer.layer.features, function(feature){
                  if(feature.data.linkId == fml.linkId)
                     floatingFeatures.push(feature);
              });
            _.each(roadLayer.layer.features, function(feature) {
              if(!_.isEmpty(selectedLinkProperty.getFeaturesToKeep())){
                if(feature.geometry.bounds.containsBounds(_.first(floatingFeatures).geometry.bounds))
                  highlightFeatureByLinkId(feature.attributes.linkId);
              }
            });
          });
          var anomalousFeatures = _.uniq(_.filter(selectedLinkProperty.getFeaturesToKeep(), function(ft){
              return ft.anomaly === 1;
            })
          );
          anomalousFeatures.forEach(function (fmf){
            editFeatureDataForGreen(fmf.linkId);
          });
        }
      }
    };

    var unselectRoadLink = function() {
      currentRenderIntent = 'default';
      selectedLinkProperty.close();
      _.map(roadLayer.layer.features,function (feature){
        if(feature.data.gapTransfering) {
          feature.data.gapTransfering = false;
          feature.attributes.gapTransfering = false;
          feature.data.anomaly = feature.data.prevAnomaly;
          feature.attributes.anomaly = feature.attributes.prevAnomaly;
        }
      });
      indicatorLayer.clearMarkers();
      unhighlightFeatures();
      roadLayer.redraw();
    };

    var unselectAllRoadLinks = function(options) {
      // we'll want an option to supress notification here
      var layers = this.layers || [this.layer],
        layer, feature, l, numExcept;
      for(l=0; l<layers.length; ++l) {
        layer = layers[l];
        numExcept = 0;
        //layer.selectedFeatures is null when layer is destroyed and
        //one of it's preremovelayer listener calls setLayer
        //with another layer on this control
        if(layer.selectedFeatures !== null) {
          if(applicationModel.isActiveButtons() && layer.selectedFeatures.length > numExcept)
          {
            return Confirm();
          }else {
            while (layer.selectedFeatures.length > numExcept) {
              feature = layer.selectedFeatures[numExcept];
              if (!options || options.except != feature) {
                this.unselect(feature);
              } else {
                ++numExcept;
              }
            }
          }
        }
      }
    };

    var selectControl = new OpenLayers.Control.SelectFeature(roadLayer.layer, {
      onSelect: selectRoadLink,
      onUnselect: unselectRoadLink,
      unselectAll: unselectAllRoadLinks
    });

    map.addControl(selectControl);
    var doubleClickSelectControl = new DoubleClickSelectControl(selectControl, map);
    this.selectControl = selectControl;

    this.activateSelection = function() {
      doubleClickSelectControl.activate();
    };
    this.deactivateSelection = function() {
      doubleClickSelectControl.deactivate();
    };

    var highlightFeatures = function() {
      _.each(roadLayer.layer.features, function(x) {
        var gapTransfering = x.data.gapTransfering;
        var canIHighlight = !_.isUndefined(x.attributes.linkId) ? selectedLinkProperty.isSelectedByLinkId(x.attributes.linkId) : selectedLinkProperty.isSelectedById(x.attributes.id);
        if(gapTransfering || canIHighlight){
          selectControl.highlight(x);
        } else {
          selectControl.unhighlight(x);
        }
      });
    };

    var highlightFeatureByLinkId = function (linkId) {
      _.each(roadLayer.layer.features, function(x) {
        if(x.attributes.linkId == linkId){
          selectControl.highlight(x);
        }
      });
    };

    var unhighlightFeatureByLinkId = function (linkId) {
      _.each(roadLayer.layer.features, function(x) {
        if(x.attributes.linkId == linkId){
          selectControl.unhighlight(x);
        }
      });
    };

    var unhighlightFeatures = function() {
      _.each(roadLayer.layer.features, function(x) {
        selectControl.unhighlight(x);
      });
    };

    var draw = function(action, changedTargetIds) {
      cachedLinkPropertyMarker = new LinkPropertyMarker(selectedLinkProperty);
      cachedMarker = new LinkPropertyMarker(selectedLinkProperty);
      var roadLinks = [];
      if(!applicationModel.isActiveButtons() && window.eventbus.on('map:moved')) {
        prepareRoadLinkDraw();
      }
      if(!_.isUndefined(action) && _.isEqual(action, applicationModel.actionCalculated)){
        roadLinks = roadCollection.getAllTmp();
      } else {
        roadLinks = roadCollection.getAll();
      }
      if(!_.isUndefined(action) && _.isEqual(action, applicationModel.actionCalculating))
        _.each(roadLinks, function(roadlink){
          if(!_.isUndefined(roadlink.gapTransfering) && roadlink.gapTransfering === true){
            roadlink.gapTransfering = null;
          }
        });

      roadLayer.drawRoadLinks(roadLinks, zoom, action);
      drawDashedLineFeaturesIfApplicable(roadLinks);

      floatingMarkerLayer.clearMarkers();
      anomalousMarkerLayer.clearMarkers();

      if(zoom > zoomlevels.minZoomForAssets) {
        var floatingRoadMarkers = _.filter(roadLinks, function(roadlink) {
          return roadlink.roadLinkType === -1;
        });

        var anomalousRoadMarkers = _.filter(roadLinks, function(roadlink) {
          return roadlink.anomaly === 1;
        });

        _.each(floatingRoadMarkers, function(floatlink) {
          var sources = !_.isEmpty(selectedLinkProperty.getSources()) ? selectedLinkProperty.getSources() : selectedLinkProperty.get();
          var source = sources.find(function(s){
            return s.linkId === floatlink.linkId ;
          });
          var tempFlag = roadCollection.getAllTmp().find(function(road){
            return road.linkId === floatlink.linkId;
          });

          if((_.isUndefined(tempFlag) || _.isUndefined(source))){
            var mouseClickHandler = createMouseClickHandler(floatlink);
            var marker = cachedLinkPropertyMarker.createMarker(floatlink);
            marker.events.register('click',marker, mouseClickHandler);
            marker.events.registerPriority('dblclick',marker, mouseClickHandler);
            floatingMarkerLayer.addMarker(marker);
          }
        });

        _.each(anomalousRoadMarkers, function(anomalouslink) {
          var targets =selectedLinkProperty.getTargets();
          var target = targets.find(function(s){
            return s.linkId === anomalouslink.linkId ;
          });
          if((_.isUndefined(target))){
            var mouseClickHandler = createMouseClickHandler(anomalouslink);
            var marker = cachedMarker.createMarker(anomalouslink);
            marker.events.register('click',marker, mouseClickHandler);
            marker.events.registerPriority('dblclick',marker, mouseClickHandler);
            anomalousMarkerLayer.addMarker(marker);
          }
        });
      }

      if (zoom > zoomlevels.minZoomForAssets) {
        me.drawCalibrationMarkers(roadLayer.layer, roadLinks);
      }
      if(!_.isUndefined(action) && _.isEqual(action, applicationModel.actionCalculated)){
        redrawSelected(action);
      } else {
        redrawSelected();
      }
      eventbus.trigger('linkProperties:available');
    };

    var createMouseClickHandler = function(floatlink) {
      return function(event){
        if(floatlink.anomaly !== 1) {
          selectControl.unselectAll();
        }
        var feature = _.find(roadLayer.layer.features, function (feat) {
          return feat.attributes.linkId === floatlink.linkId;
        });
        if(event.type === 'click' || event.type === 'dblclick'){
          selectControl.select(_.assign({singleLinkSelect: true}, feature));
        } else {
          selectControl.unselectAll();
        }
      };
    };

    this.refreshView = function() {
      // Generalize the zoom levels as the resolutions and zoom levels differ between map tile sources
      zoom = 11 - Math.round(Math.log(map.getResolution()) * Math.LOG2E);
      roadCollection.fetch(map.getExtent(), zoom);
    };

    this.isDirty = function() {
      return selectedLinkProperty.isDirty();
    };

    var createDashedLineFeatures = function(roadLinks, dashedLineFeature) {
      return _.flatten(_.map(roadLinks, function(roadLink) {
        var points = _.map(roadLink.points, function(point) {
          return new OpenLayers.Geometry.Point(point.x, point.y);
        });
        var attributes = {
          dashedLineFeature: roadLink[dashedLineFeature],
          linkId: roadLink.linkId,
          type: 'overlay',
          linkType: roadLink.linkType,
          zIndex: 1
        };
        return new OpenLayers.Feature.Vector(new OpenLayers.Geometry.LineString(points), attributes);
      }));
    };

    var unknownFeatureSizeLookup = {
      9: { strokeWidth: 3, pointRadius: 0 },
      10: { strokeWidth: 5, pointRadius: 10 },
      11: { strokeWidth: 7, pointRadius: 14 },
      12: { strokeWidth: 10, pointRadius: 16 },
      13: { strokeWidth: 10, pointRadius: 16 },
      14: { strokeWidth: 14, pointRadius: 22 },
      15: { strokeWidth: 14, pointRadius: 22 }
    };

    var browseStyle = new OpenLayers.Style(OpenLayers.Util.applyDefaults());
    var browseStyleMap = new OpenLayers.StyleMap({ default: browseStyle });
    browseStyleMap.addUniqueValueRules('default', 'level', unknownFeatureSizeLookup, applicationModel.zoom);

    var typeFilter = function(type) {
      return new OpenLayers.Filter.Comparison(
        { type: OpenLayers.Filter.Comparison.EQUAL_TO, property: 'type', value: type });
    };

    var unknownLimitStyleRule = new OpenLayers.Rule({
      filter: typeFilter('roadAddressAnomaly'),
      symbolizer: { externalGraphic: 'images/speed-limits/unknown.svg' }
    });

    browseStyle.addRules([unknownLimitStyleRule]);
    var vectorLayer = new OpenLayers.Layer.Vector(layerName, { styleMap: browseStyleMap });
    vectorLayer.setOpacity(1);
    vectorLayer.setVisibility(true);

    var drawDashedLineFeatures = function(roadLinks) {
      var dashedRoadClasses = [7, 8, 9, 10];
      var dashedRoadLinks = _.filter(roadLinks, function(roadLink) {
        return _.contains(dashedRoadClasses, roadLink.roadClass);
      });
      roadLayer.layer.addFeatures(createDashedLineFeatures(dashedRoadLinks, 'functionalClass'));
    };

    var drawUnderConstructionFeatures = function(roadLinks) {
      var constructionTypeValues = [1];
      var unknownType = 'unknownConstructionType';
      var dashedUnknownUnderConstructionRoadLinks = _.filter(roadLinks, function(roadLink) {
        return _.contains(constructionTypeValues, roadLink.constructionType) && roadLink.anomaly === 1;
      });
      var type = 'constructionType';
      var dashedUnderConstructionRoadLinks = _.filter(roadLinks, function(roadLink) {
        return _.contains(constructionTypeValues, roadLink.constructionType) && roadLink.roadClass === 99 && roadLink.anomaly === 0;
      });
      roadLayer.layer.addFeatures(createDarkDashedLineFeatures(dashedUnknownUnderConstructionRoadLinks, unknownType));
      roadLayer.layer.addFeatures(createDarkDashedLineFeatures(dashedUnderConstructionRoadLinks, type));
    };

    var drawDashedLineFeaturesForType = function(roadLinks) {
      var dashedLinkTypes = [2, 4, 6, 8, 12, 21];
      var dashedRoadLinks = _.filter(roadLinks, function(roadLink) {
        return _.contains(dashedLinkTypes, roadLink.linkType);
      });
      roadLayer.layer.addFeatures(createDashedLineFeatures(dashedRoadLinks, 'linkType'));
    };
    var drawBorderLineFeatures = function(roadLinks) {
      var adminClass = 'Municipality';
      var roadClasses = [1,2,3,4,5,6,7,8,9,10,11];
      var borderLineFeatures = _.filter(roadLinks, function(roadLink) {
        return _.contains(adminClass, roadLink.administrativeClass) && _.contains(roadClasses, roadLink.roadClass) && roadLink.roadLinkType !== -1 && !(roadLink.roadLinkType === -1 && roadLink.roadClasses === 3);
      });
      var features = createBorderLineFeatures(borderLineFeatures, 'functionalClass');
      roadLayer.layer.addFeatures(features);
    };
    var createDarkDashedLineFeatures = function(roadLinks, type){
      return darkDashedLineFeatures(roadLinks, type).concat(calculateMidPointForMarker(roadLinks, type));
    };
    var darkDashedLineFeatures = function(roadLinks, darkDashedLineFeature) {
      return _.flatten(_.map(roadLinks, function(roadLink) {
        var points = _.map(roadLink.points, function(point) {
          return new OpenLayers.Geometry.Point(point.x, point.y);
        });
        var attributes = {
          dashedLineFeature: roadLink[darkDashedLineFeature],
          linkId: roadLink.linkId,
          type: 'overlay-dark',
          linkType: roadLink.linkType,
          zIndex: 1
        };
        return new OpenLayers.Feature.Vector(new OpenLayers.Geometry.LineString(points), attributes);
      }));
    };
    var calculateMidPointForMarker = function(roadLinks, type){
      return _.map(roadLinks, function(link) {
        var points = _.map(link.points, function(point) {
          return new OpenLayers.Geometry.Point(point.x, point.y);
        });
        var road = new OpenLayers.Geometry.LineString(points);
        var signPosition = GeometryUtils.calculateMidpointOfLineString(road);
        var attributes = {type: type, linkId: link.linkId};
        return new OpenLayers.Feature.Vector(new OpenLayers.Geometry.Point(signPosition.x, signPosition.y), attributes);
      });
    };
    var createBorderLineFeatures = function(roadLinks) {
      return _.flatten(_.map(roadLinks, function(roadLink) {
        var points = _.map(roadLink.points, function(point) {
          return new OpenLayers.Geometry.Point(point.x, point.y);
        });
        var attributes = {
          linkId: roadLink.linkId,
          type: 'underlay',
          linkType: roadLink.roadLinkType
        };
        return new OpenLayers.Feature.Vector(new OpenLayers.Geometry.LineString(points), attributes);
      }));
    };

    var getSelectedFeatures = function() {
      return _.filter(roadLayer.layer.features, function (feature) {
        return selectedLinkProperty.isSelectedByLinkId(feature.attributes.linkId);
      });
    };

    var reselectRoadLink = function() {
      me.activateSelection();
      var originalOnSelectHandler = selectControl.onSelect;
      selectControl.onSelect = function() {};
      var features = getSelectedFeatures();
      var indicators = jQuery.extend(true, [], indicatorLayer.markers);
      indicatorLayer.clearMarkers();
      if(indicators.length !== 0){
        _.forEach(indicators, function(indicator){
          indicatorLayer.addMarker(createIndicatorFromBounds(indicator.bounds, indicator.div.innerText));
        });
      }
      if (!_.isEmpty(features)) {
        currentRenderIntent = 'select';
        selectControl.select(_.first(features));

        if(!applicationModel.isReadOnly() && _.first(features).attributes.roadLinkType == -1){
          highlightAnomalousFeaturesByFloating();
        }else {
          highlightFeatures();
        }
      }
      selectControl.onSelect = originalOnSelectHandler;
      if (selectedLinkProperty.isDirty()) {
        me.deactivateSelection();
      }
    };

    var prepareRoadLinkDraw = function() {
      me.deactivateSelection();
    };

    var drawDashedLineFeaturesIfApplicable = function (roadLinks) {
      drawDashedLineFeatures(roadLinks);
      drawBorderLineFeatures(roadLinks);
      drawUnderConstructionFeatures(roadLinks);
    };

    this.layerStarted = function(eventListener) {
      indicatorLayer.setZIndex(1000);
      var linkPropertyChangeHandler = _.partial(handleLinkPropertyChanged, eventListener);
      var linkPropertyEditConclusion = _.partial(concludeLinkPropertyEdit, eventListener);
      eventListener.listenTo(eventbus, 'linkProperties:changed', linkPropertyChangeHandler);
      eventListener.listenTo(eventbus, 'linkProperties:cancelled linkProperties:saved', linkPropertyEditConclusion);
      eventListener.listenTo(eventbus, 'linkProperties:saved', refreshViewAfterSaving);
      eventListener.listenTo(eventbus, 'linkProperties:multiSelected', function(link) {
        if (!_.isEmpty(selectedLinkProperty.get())){
          var feature = _.find(roadLayer.layer.features, function (feature) {
            return link.linkId !== 0 && feature.attributes.linkId === link.linkId;
          });
          if (feature) {
            _.each(selectControl.layer.selectedFeatures, function (selectedFeature) {
              if (selectedFeature.attributes.linkId !== feature.attributes.linkId) {
                selectControl.select(feature);
              }
            });
          }
        }
        clearIndicators();
      });

      eventListener.listenTo(eventbus, 'linkProperties:reselect', reselectRoadLink);
      eventListener.listenTo(eventbus, 'roadLinks:fetched', draw);
      eventListener.listenTo(eventbus, 'roadLinks:drawAfterGapCanceling', function() {
        currentRenderIntent = 'default';
        _.map(roadLayer.layer.features,function (feature){
          if(feature.data.gapTransfering) {
            feature.data.gapTransfering = false;
            feature.attributes.gapTransfering = false;
            feature.data.anomaly = feature.data.prevAnomaly;
            feature.attributes.anomaly = feature.attributes.prevAnomaly;
          }
        });
        unhighlightFeatures();
        roadLayer.redraw();
        var current = selectedLinkProperty.get();
        _.forEach(current, function(road){
          var feature = _.find(roadLayer.layer.features, function (feature) {
            return road.linkId !== 0 && feature.attributes.linkId === road.linkId;
          });
          if (feature) {
            _.each(selectControl.layer.selectedFeatures, function (selectedFeature) {
              if (selectedFeature.attributes.linkId !== feature.attributes.linkId) {
                selectControl.select(feature);
              }
            });
          }
        });
        indicatorLayer.clearMarkers();
      });




      eventListener.listenTo(eventbus, 'linkProperties:dataset:changed', draw);
      eventListener.listenTo(eventbus, 'linkProperties:updateFailed', cancelSelection);
      eventListener.listenTo(eventbus, 'map:clicked', handleMapClick);
      eventListener.listenTo(eventbus, 'adjacents:nextSelected', function(sources, adjacents, targets) {
        applicationModel.addSpinner();
        redrawNextSelectedTarget(targets, adjacents);
        if(applicationModel.getCurrentAction()!==applicationModel.actionCalculated){
          drawIndicators(adjacents);
          selectedLinkProperty.addTargets(targets, adjacents);
        }
      });
      eventListener.listenTo(eventbus, 'adjacents:added adjacents:aditionalSourceFound', function(sources,targets, aditionalLinkId){
        drawIndicators(targets);
        _.map(_.rest(selectedLinkProperty.getFeaturesToKeep()), function (roads){
          editFeatureDataForGreen(roads);
          highlightFeatureByLinkId(roads.linkId);
        });
        highlightFeatureByLinkId(aditionalLinkId);
      });
      eventListener.listenTo(eventbus, 'adjacents:roadTransfer', function(newRoads,changedIds){
        var roadLinks = roadCollection.getAll();
        var afterTransferLinks=  _.filter(roadLinks, function(roadlink){
          return !_.contains(changedIds, roadlink.linkId.toString());
        });
        _.map(newRoads, function(road){
          afterTransferLinks.push(road);
        });
        indicatorLayer.clearMarkers();
        roadCollection.setTmpRoadAddresses(afterTransferLinks);
        applicationModel.setCurrentAction(applicationModel.actionCalculated);
        selectedLinkProperty.cancelGreenRoad(applicationModel.actionCalculated, changedIds);
        roadCollection.setChangedIds(changedIds);
      });

      eventbus.on('linkProperties:reselectRoadLink', function(){
        reselectRoadLink();
        roadLayer.redraw();
      });

      eventbus.on('linkProperties:highlightAnomalousByFloating', function(featuresToKeep){
        var floatingFeatures =[];
        _.each(roadLayer.layer.features, function(feature){
          if(feature.data.linkId == featuresToKeep[0].linkId)
            floatingFeatures.push(feature);
        });
        _.each(roadLayer.layer.features, function(feature) {
          if(!_.isEmpty(floatingFeatures)){
            if(feature.geometry.bounds.containsBounds((_.first(floatingFeatures).geometry.bounds)))
              selectControl.highlight(feature);
          }
        });
      });

      eventListener.listenTo(eventbus, 'roadLink:editModeAdjacents', function() {

        if (applicationModel.isReadOnly() && !applicationModel.isActiveButtons()) {
          indicatorLayer.clearMarkers();
          var floatingsLinkIds = _.map(_.filter(selectedLinkProperty.getFeaturesToKeep(), function (feature) {
            return feature.roadLinkType == -1;
          }), function (floating) {
            return floating.linkId;
          });
          _.defer(function(){
            _.map(roadLayer.layer.features, function (feature) {
              if (_.contains(floatingsLinkIds, feature.attributes.linkId)) {
                selectControl.select(feature);
                if(!applicationModel.isReadOnly()){
                  //This should be made in the "Valinta Valmis" Trigger.
                  highlightAnomalousFeaturesByFloating();
                }
              }
            });
          });
        } else {
          var selectedFloatings = _.filter(selectedLinkProperty.get(), function(features){
            return features.roadLinkType == -1;
          });
          _.each(selectedFloatings, function(sf){
            selectedLinkProperty.getFeaturesToKeep().push(sf);
          });
          //This should be made in the "Valinta Valmis" Trigger.
          if(!_.isEmpty(selectedFloatings)){
            highlightAnomalousFeaturesByFloating();  
          }
        }
      });
      eventListener.listenTo(eventbus, 'roadLinks:deleteSelection', function () {
        prepareRoadLinkDraw();
      });

      eventListener.listenTo(eventbus, 'roadLinks:unSelectIndicators', function (originalFeature) {
        prepareRoadLinkDraw();
        clearIndicators();
        selectControl.unselectAll();
        roadCollection.getAll();
        var features =[];
        _.each(roadLayer.layer.features, function(feature){
          if(feature.data.linkId == originalFeature.linkId)
            features.push(feature);
        });

        _.each(roadLayer.layer.features, function(anomalousFeature) {
          if(!_.isEmpty(features)){
            if(anomalousFeature.geometry.bounds.containsBounds((_.first(features).geometry.bounds)))
              features.push(anomalousFeature);
          }
        });

        if(!_.isEmpty(features)) {
          currentRenderIntent = 'select';
          selectControl.select(_.first(features));
          _.each(features, function(x) {
            selectControl.highlight(x);
          });
        }
      });
      eventListener.listenTo(eventbus, 'linkProperties:cancelled', unselectRoadLink);
    };

    var clearIndicators = function () {
      indicatorLayer.clearMarkers();
    };

    var drawIndicators= function(links){
      indicatorLayer.clearMarkers();
      if(applicationModel.getCurrentAction()!==applicationModel.actionCalculated){
        var indicators = me.mapOverLinkMiddlePoints(links, function(link, middlePoint) {
          var bounds = OpenLayers.Bounds.fromArray([middlePoint.x, middlePoint.y, middlePoint.x, middlePoint.y]);
          return createIndicatorFromBounds(bounds, link.marker);
        });
        _.forEach(indicators, function(indicator){
          indicatorLayer.addMarker(indicator);
        });
      }
    };

    var createIndicatorFromBounds = function(bounds, marker) {
      var markerTemplate = _.template('<span class="marker" style="margin-left: -1em; margin-top: -1em; position: absolute;"><%= marker %></span>');
      var box = new OpenLayers.Marker.Box(bounds, "00000000");
      $(box.div).html(markerTemplate({'marker': marker}));
      $(box.div).css('overflow', 'visible');
      return box;
    };

    var handleMapClick = function (){
      if(!applicationModel.isActiveButtons()){
        selectedLinkProperty.cancel();
        selectedLinkProperty.close();
      }

    };

    var cancelSelection = function() {
      if(!applicationModel.isActiveButtons()) {
        selectedLinkProperty.cancel();
        selectedLinkProperty.close();
        unselectRoadLink();
      }
    };

    var refreshViewAfterSaving = function() {
      unselectRoadLink();
      me.refreshView();
    };

    var handleLinkPropertyChanged = function(eventListener) {
      redrawSelected();
      me.deactivateSelection();
      eventListener.stopListening(eventbus, 'map:clicked', me.displayConfirmMessage);
      eventListener.listenTo(eventbus, 'map:clicked', me.displayConfirmMessage);
    };

    var concludeLinkPropertyEdit = function(eventListener) {
      me.activateSelection();
      eventListener.stopListening(eventbus, 'map:clicked', me.displayConfirmMessage);
      redrawSelected();
    };

    var redrawSelected = function(action) {
      var selectedRoadLinks = [];
      if(!applicationModel.isActiveButtons()){
        roadLayer.layer.removeFeatures(getSelectedFeatures());
      }
      if((!_.isUndefined(action) && _.isEqual(action, applicationModel.actionCalculated)) || !_.isEmpty(roadCollection.getAllTmp())){
        selectedRoadLinks = roadCollection.getAllTmp();
      } else {
        selectedRoadLinks = selectedLinkProperty.get();
      }
      _.each(selectedRoadLinks,  function(selectedLink) { roadLayer.drawRoadLink(selectedLink); });
      drawDashedLineFeaturesIfApplicable(selectedRoadLinks);
      me.drawSigns(roadLayer.layer, selectedRoadLinks);
      reselectRoadLink();
    };

    var redrawNextSelectedTarget= function(targets, adjacents) {
      editFeatureDataForGreen(targets);
      reselectRoadLink();
      draw();
    };

    var editFeatureDataForGreen = function (targets) {
      var features =[];
      if(targets !== 0){
        _.map(roadLayer.layer.features, function(feature){
          if(feature.attributes.linkId == targets){
            feature.attributes.prevAnomaly = feature.attributes.anomaly;
            feature.data.prevAnomaly = feature.data.anomaly;
            feature.attributes.gapTransfering = true;
            feature.data.gapTransfering = true;
            selectedLinkProperty.getFeaturesToKeep().push(feature.data);
            features.push(feature);
          }
        });
      }
      if(features.length === 0)
        return undefined;
      else return _.first(features);
    };

    var highlightAnomalousFeaturesByFloating = function() {
      var floatingProperty = _.map(_.filter(selectedLinkProperty.get(), function(floatingLinkProperty) {
        return floatingLinkProperty.roadLinkType == -1;
      }));
      var floatingFeatures =[];
      _.each(roadLayer.layer.features, function(feature){
        if(feature.data.linkId == _.first(floatingProperty).linkId)
          floatingFeatures.push(feature);
      });
      _.each(roadLayer.layer.features, function(feature) {
        if(!_.isEmpty(floatingFeatures)){
          if(feature.geometry.bounds.containsBounds((_.first(floatingFeatures).geometry.bounds)))
            selectControl.highlight(feature);
        }
      });
    };

    this.removeLayerFeatures = function() {
      roadLayer.layer.removeFeatures(roadLayer.layer.getFeaturesByAttribute('type', 'overlay'));
      indicatorLayer.clearMarkers();
    };

    var show = function(map) {
      vectorLayer.setVisibility(true);
      me.show(map);
      eventListener.listenTo(eventbus, 'map:clicked', cancelSelection);
    };

    var hideLayer = function() {
      unselectRoadLink();
      me.stop();
      me.hide();
    };

    return {
      show: show,
      hide: hideLayer,
      minZoomForContent: me.minZoomForContent
    };
  };
})(this);
