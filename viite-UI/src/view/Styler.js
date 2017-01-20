(function(root) {
  root.Styler = function() {

    var borderWidth = 5;

    /**
     * Inspired on the LinkPropertyLayerStyles roadClassRules, unknownRoadAddressAnomalyRules and constructionTypeRules.
     * @param roadClass The roadLink roadClass.
     * @param anomaly The roadLink anomaly value (if 1 then this is an anomalous roadlink).
     * @param constructionType The roadLink constructionType.
     * @returns {string} The default solid color of a line in the RGBA format.
     */
    var generateStrokeColor = function (roadClass, anomaly, constructionType) {
      if (anomaly !== 1) {
        if(constructionType === 1) {
          return 'rgba(164, 164, 162, 1)';
        }
        else {
          switch (roadClass) {
            case 1 : return 'rgba(255, 0, 0, 1)';
            case 2 : return 'rgba(255, 102, 0, 1)';
            case 3 : return 'rgba(255, 153, 51, 1)';
            case 4 : return 'rgba(0, 17, 187, 1)';
            case 5 : return 'rgba(51, 204, 204, 1)';
            case 6 : return 'rgba(224, 29, 217, 1)';
            case 7 : return 'rgba(0, 204, 221, 1)';
            case 8 : return 'rgba(136, 136, 136, 1)';
            case 9 : return 'rgba(255, 85, 221, 1)';
            case 10 : return 'rgba(255, 85, 221, 1)';
            case 11 : return 'rgba(68, 68, 68, 1)';
            case 99 : return 'rgba(164, 164, 162, 1)';
          }
        }
      } else {
        if(constructionType === 1) {
          return 'rgba(255, 153, 0, 1)';
        } else {
          return 'rgba(0, 0, 0, 1)';
        }
      }
    };

    /**
     * Inspired in the LinkPropertyLayerStyles complementaryRoadAddressRules and unknownRoadAddressAnomalyRules,
     * @param roadLinkType The roadLink roadLinkType.
     * @param anomaly The roadLink anomaly value (if 1 then this is an anomalous roadlink).
     * @returns {number} The zIndex for the feature.
     */
    var determineZIndex = function (roadLinkType, anomaly){
      var zIndex = 0;
      if (anomaly === 0) {
        if (roadLinkType === 3)
          zIndex = 4;
        else if(roadLinkType === -1) {
          zIndex = 2;
        }
      } else {
        zIndex = 3;
      }
      return zIndex;
    };
    /**
     * Will indicate what stroke dimension will be used based on the zoom level provided.
     * @param zoomLevel The actual zoom level.
     * @returns {number} The stroke width of a line.
     */
    var strokeWidthByZoomLevel = function (zoomLevel){
      switch (zoomLevel) {
        case 6 : return 1  ;
        case 7 : return 2  ;
        case 8 : return 3  ;
        case 9 : return 3  ;
        case 10: return 5  ;
        case 11: return 8  ;
        case 12: return 10 ;
        case 13: return 10 ;
        case 14: return 14 ;
        case 15: return 14 ;
      }
    };

    /**
     * Method that changes color properties via a multiplicative factor.
     * @param lineColor The RGBA string of the color.
     * @param mult The multiplicative parameter. To darken use values between 0 and 1 to brighten use values > 1
     * @param changeOpacity If we want to change the opacity.
     * @param changeColor If we want to change the color.
     * @returns {string} The changed color.
     */
    var modifyColorProperties = function(lineColor, mult, changeColor, changeOpacity){
      var rgba = lineColor.slice(5, lineColor.length-1).split(", ");
      var red = parseInt(rgba[0]) * (changeColor ? mult : 1);
      var green = parseInt(rgba[1]) * (changeColor ? mult : 1);
      var blue = parseInt(rgba[2]) * (changeColor ? mult : 1);
      var opacity = parseInt(rgba[3]) * (changeOpacity ? mult : 1);
      return 'rgba(' + red + ', ' + green + ', ' + blue + ', ' + opacity + ')';
    };

    /**
     * Method evoked by feature that will determine what kind of style said feature will have.
     * @param roadLinkData The roadLink details of a feature
     * @param currentZoom The value of the current application zoom.
     * @returns {*[ol.style.Style, ol.style.Style]} And array of ol.style.Style, the first is for the border the second is for the line itself.
     */
    var generateStyleByFeature = function(roadLinkData, currentZoom){
      var strokeWidth = strokeWidthByZoomLevel(currentZoom);
      var lineColor = generateStrokeColor(roadLinkData.roadClass, roadLinkData.anomaly, roadLinkData.constructionType);
      var borderColor = modifyColorProperties(lineColor, 0.5, false, true);

       var lineBorder = new ol.style.Stroke({
        width: strokeWidth + borderWidth,
        color: borderColor
      });
      var line = new ol.style.Stroke({
        width: strokeWidth,
        color: lineColor
      });
      var borderStyle = new ol.style.Style({
        stroke: lineBorder
      });
      var lineStyle = new ol.style.Style({
        stroke: line
      });
      var zIndex = determineZIndex(roadLinkData.roadLinkType, roadLinkData.anomaly);
      borderStyle.setZIndex(zIndex);
      lineStyle.setZIndex(zIndex);
      return [borderStyle, lineStyle];
    };

    return {
      generateStyleByFeature: generateStyleByFeature
    };
  };
})(this);