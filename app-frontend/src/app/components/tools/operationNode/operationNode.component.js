import operationNodeTpl from './operationNode.html';

export default {
    templateUrl: operationNodeTpl,
    controller: 'OperationNodeController',
    bindingr: {
        model: '<',
        onChange: '&'
    }
};
