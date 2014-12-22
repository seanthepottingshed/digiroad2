(function(root) {
  var environmentProperty = function() {
    var properties = {
      production: 'UA-57190819-4',
      training: 'UA-57190819-3',
      staging: 'UA-57190819-2',
      unknown: 'UA-57190819-1'
    };
    return properties[Environment.name()];
  };

  var environmentConfiguration = function() {
    var configurations = {
      production: 'auto',
      training: 'auto',
      staging: 'auto',
      unknown: 'none'
    };
    return configurations[Environment.name()];
  };

  var start = function() {
    ga('create', environmentProperty(), environmentConfiguration());
    ga('send', 'pageview');
    if(window.eventbus) {
      eventbus.on('all', function(eventName) {
        var excludedEvents = [
          'map:mouseMoved',
          'map:moved',
          'map:clicked',
          'asset:saving',
          'road:active',
          'asset:moved',
          'roadLinks:beforeDraw',
          'roadLinks:drawn'];
        if (!_.contains(excludedEvents, eventName)) {
          var splitName = eventName.split(':');
          var category = splitName[0];
          var action = splitName[1];
          ga('send', 'event', category, action);
        }
      });
    }
  };

  root.Analytics = {
    start: start
  };
}(this));