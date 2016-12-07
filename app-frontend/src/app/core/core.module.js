const shared = angular.module('core.shared', []);

require('./services/scene.service')(shared);
require('./services/project.service')(shared);
require('./services/user.service')(shared);
require('./services/config.provider')(shared);
require('./services/auth.service')(shared);
require('./services/token.service')(shared);
require('./services/layer.service')(shared);

export default shared;
