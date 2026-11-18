module jsanca.katabasis.cli {
    requires jsanca.katabasis.core;
    requires jsanca.katabasis.storage.json;
    requires info.picocli;

    opens jsanca.katabasis.cli to info.picocli;
}
