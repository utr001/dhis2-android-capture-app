package com.dhis2.usescases.eventInitial;

import com.dhis2.data.dagger.PerActivity;
import com.dhis2.usescases.eventInitial.tablet.EventInitialTabletActivity;

import dagger.Subcomponent;

/**
 * Created by Cristian on 13/02/2018.
 *
 */

@PerActivity
@Subcomponent(modules = EventInitialModule.class)
public interface EventInitialComponent {
    void inject(EventInitialActivity activity);
    void inject(EventInitialTabletActivity activity);
}