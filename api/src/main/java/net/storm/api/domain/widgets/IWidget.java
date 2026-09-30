package net.storm.api.domain.widgets;

import java.awt.Rectangle;

public interface IWidget {
    int getId();

    int getItemId();

    String getName();

    String getText();

    boolean isHidden();

    Rectangle getBounds();

    boolean hasAction(String action);

    boolean interact(String action);

    IWidget[] getChildren();

    /** Child/slot index used as menuAction param0, or -1. */
    default int getIndex() {
        return -1;
    }
}
