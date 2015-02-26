(function (root) {
  root.ManoeuvreForm = function(selectedManoeuvreSource) {
    var template = '' +
      '<header><span>Linkin ID: <%= mmlId %></span></header>' +
      '<div class="wrapper read-only"><div class="form form-horizontal form-dark form-manoeuvre"><div></div></div></div>';
    var manouvreTemplate = '' +
      '<div class="form-group manoeuvre">' +
        '<label class="control-label">Kääntyminen kielletty linkille </label>' +
        '<p class="form-control-static"><%= destMmlId %></p>' +
      '</div>';
    var adjacentLinkTemplate = '' +
      '<div class="form-group adjacent-link style="display: none">' +
        '<label class="control-label">Kääntyminen kielletty linkille </label>' +
        '<p class="form-control-static"><%= mmlId %></p>' +
        '<div class="checkbox" >' +
          '<input type="checkbox" roadLinkId="<%= id %>" <% print(checked ? "checked" : "") %>/>' +
        '</div>' +
      '</div>';

    var bindEvents = function() {
      var rootElement = $('#feature-attributes');

      function toggleMode(readOnly) {
        rootElement.find('.adjacent-link').toggle(!readOnly);
        rootElement.find('.manoeuvre').toggle(readOnly);
      }
      eventbus.on('application:readOnly', toggleMode);

      eventbus.on('manoeuvres:selected manoeuvres:cancelled manoeuvres:saved', function(roadLink) {
        rootElement.html(_.template(template, roadLink));
        _.each(roadLink.manoeuvres, function(manoeuvre) {
          rootElement.find('.form').append(_.template(manouvreTemplate, manoeuvre));
        });
        _.each(roadLink.adjacent, function(adjacentLink) {
          var attributes = _.merge({}, adjacentLink, {
            checked: _.some(roadLink.manoeuvres, function(manoeuvre) { return manoeuvre.destMmlId === adjacentLink.mmlId; })
          });
          rootElement.find('.form').append(_.template(adjacentLinkTemplate, attributes));
        });
        toggleMode(applicationModel.isReadOnly());
        rootElement.find('.adjacent-link input').change(function(event) {
          var eventTarget = $(event.currentTarget);
          var destRoadLinkId = eventTarget.attr('roadLinkId');
          if (eventTarget.attr('checked') === 'checked') {
            selectedManoeuvreSource.add(destRoadLinkId);
          } else {
            selectedManoeuvreSource.remove(destRoadLinkId);
          }
        });
      });
      eventbus.on('manoeuvres:unselected', function() {
        rootElement.empty();
      });
    };

    bindEvents();
  };
})(this);
