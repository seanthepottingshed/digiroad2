(function(root) {
  root.SpeedLimitsCollection = function(backend) {
    var speedLimits = {};

    this.fetch = function(boundingBox) {
      backend.getSpeedLimits(boundingBox, function(fetchedSpeedLimits) {
        var selected = _.find(_.values(speedLimits), function(speedLimit) { return speedLimit.isSelected; });

        speedLimits = _.chain(fetchedSpeedLimits)
          .groupBy('id')
          .map(function(values, key) { return [key, { id: values[0].id, links: _.pluck(values, 'points'), sideCode: values[0].sideCode, limit: values[0].limit }]; })
          .object()
          .value();

        if (selected && !speedLimits[selected.id]) {
          speedLimits[selected.id] = selected;
        } else if (selected) {
          var selectedInCollection = speedLimits[selected.id];
          selectedInCollection.isSelected = selected.limit;
          selectedInCollection.limit = selected.limit;
        }

        eventbus.trigger('speedLimits:fetched', _.values(speedLimits));
      });
    };

    this.fetchSpeedLimit = function(id, callback) {
      backend.getSpeedLimit(id, function(speedLimit) {
        callback(_.merge({}, speedLimits[id], speedLimit));
      });
    };

    this.markAsSelected = function(id) {
      speedLimits[id].isSelected = true;
    };

    this.markAsDeselected = function(id) {
      speedLimits[id].isSelected = false;
    };

    this.changeLimit = function(id, limit) {
      speedLimits[id].limit = limit;
    };
  };
})(this);
