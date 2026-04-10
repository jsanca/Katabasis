

module jsanca.katabasis.core {

    exports jsanca.katabasis.core.api.event;
    exports jsanca.katabasis.core.api.exception;
    exports jsanca.katabasis.core.api.manager;
    exports jsanca.katabasis.core.api.model;

    requires org.apache.commons.lang3;
    requires java.net.http;
    requires org.slf4j;
}