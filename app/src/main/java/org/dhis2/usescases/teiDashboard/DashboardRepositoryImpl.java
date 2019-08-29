package org.dhis2.usescases.teiDashboard;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.squareup.sqlbrite2.BriteDatabase;

import org.dhis2.R;
import org.dhis2.data.tuples.Pair;
import org.dhis2.data.tuples.Trio;
import org.dhis2.utils.CodeGenerator;
import org.dhis2.utils.DateUtils;
import org.dhis2.utils.FileResourcesUtil;
import org.dhis2.utils.ValueUtils;
import org.hisp.dhis.android.core.D2;
import org.hisp.dhis.android.core.arch.helpers.UidsHelper;
import org.hisp.dhis.android.core.category.Category;
import org.hisp.dhis.android.core.category.CategoryCombo;
import org.hisp.dhis.android.core.category.CategoryOptionCombo;
import org.hisp.dhis.android.core.common.State;
import org.hisp.dhis.android.core.common.ValueType;
import org.hisp.dhis.android.core.enrollment.Enrollment;
import org.hisp.dhis.android.core.enrollment.EnrollmentCollectionRepository;
import org.hisp.dhis.android.core.enrollment.EnrollmentStatus;
import org.hisp.dhis.android.core.enrollment.note.Note;
import org.hisp.dhis.android.core.event.Event;
import org.hisp.dhis.android.core.event.EventStatus;
import org.hisp.dhis.android.core.event.EventTableInfo;
import org.hisp.dhis.android.core.event.internal.EventFields;
import org.hisp.dhis.android.core.legendset.Legend;
import org.hisp.dhis.android.core.legendset.LegendSet;
import org.hisp.dhis.android.core.maintenance.D2Error;
import org.hisp.dhis.android.core.organisationunit.OrganisationUnit;
import org.hisp.dhis.android.core.program.Program;
import org.hisp.dhis.android.core.program.ProgramIndicator;
import org.hisp.dhis.android.core.program.ProgramStage;
import org.hisp.dhis.android.core.program.ProgramTrackedEntityAttribute;
import org.hisp.dhis.android.core.relationship.RelationshipType;
import org.hisp.dhis.android.core.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.android.core.trackedentity.TrackedEntityAttributeValue;
import org.hisp.dhis.android.core.trackedentity.TrackedEntityInstance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Predicate;
import timber.log.Timber;

import static org.hisp.dhis.android.core.arch.db.stores.internal.StoreUtils.sqLiteBind;

/**
 * QUADRAM. Created by ppajuelo on 30/11/2017.
 */

public class DashboardRepositoryImpl implements DashboardRepository {

    private static final String INSERT_NOTE = "INSERT INTO Note ( " +
            "uid, enrollment, value, storedBy, storedDate, state" +
            ") VALUES (?, ?, ?, ?, ?,?);";





    private final String EVENTS_QUERY = String.format(
            "SELECT DISTINCT %s.* FROM %s " +
                    "JOIN %s ON %s.%s = %s.%s " +
                    "WHERE %s.%s = " +
                    "(SELECT %s.%s FROM %s " +
                    "WHERE %s.%s = ? " +
                    "AND %s.%s = ? ORDER BY %s DESC LIMIT 1)" + //ProgramUid
                    "AND %s.%s != '%s' " +
                    "AND %s.%s IN (SELECT %s FROM %s WHERE %s = ?) " +
                    "ORDER BY CASE WHEN ( Event.status IN ('SCHEDULE','SKIPPED','OVERDUE')) " +
                    "THEN %s.%s " +
                    "ELSE %s.%s END DESC, %s.%s ASC",
            "Event", "Event",
            "ProgramStage", "ProgramStage", "uid", "Event", "programStage",
            "Event", "enrollment",
            "Enrollment", "uid", "Enrollment",
            "Enrollment", "program",
            "Enrollment", "trackedEntityInstance", "created",
            "Event", "state", State.TO_DELETE,
            "ProgramStage", "uid", "uid", "ProgramStage", "program",
            "Event", "dueDate",
            "Event", "eventDate", "ProgramStage", "sortOrder");

    private final String EVENTS_DISPLAY_BOX = String.format(
            "SELECT Event.* FROM %s " +
                    "JOIN %s ON %s.%s = %s.%s " +
                    "JOIN %s ON %s.%s = %s.%s " +
                    "WHERE %s.%s = ? " +
                    "AND %s.%s = ? " +
                    "AND %s.%s = ?",
            "Event",
            "Enrollment", "Enrollment", "uid", "Event", "enrollment",
            "ProgramStage", "ProgramStage", "uid", "Event", "programStage",
            "Enrollment", "program",
            "Enrollment", "trackedEntityInstance",
            "ProgramStage", "displayGenerateEventBox");


    private static final Set<String> EVENTS_TABLE = new HashSet<>(Arrays.asList("Event", "Enrollment"));
    private static final Set<String> EVENTS_PROGRAM_STAGE_TABLE = new HashSet<>(Arrays.asList("Event", "Enrollment", "ProgramStage"));

    private final String ATTRIBUTE_VALUES_QUERY = String.format(
            "SELECT TrackedEntityAttributeValue.*, TrackedEntityAttribute.valueType, TrackedEntityAttribute.optionSet FROM %s " +
                    "JOIN %s ON %s.%s = %s.%s " +
                    "JOIN %s ON %s.%s = %s.%s " +
                    "WHERE %s.%s = ? " +
                    "AND %s.%s = ? " +
                    "AND %s.%s = 1 " +
                    "ORDER BY %s.%s",
            "TrackedEntityAttributeValue",
            "ProgramTrackedEntityAttribute", "ProgramTrackedEntityAttribute", "trackedEntityAttribute", "TrackedEntityAttributeValue", "trackedEntityAttribute",
            "TrackedEntityAttribute", "TrackedEntityAttribute", "uid", "TrackedEntityAttributeValue", "trackedEntityAttribute",
            "ProgramTrackedEntityAttribute", "program",
            "TrackedEntityAttributeValue", "trackedEntityInstance",
            "ProgramTrackedEntityAttribute", "displayInList",
            "ProgramTrackedEntityAttribute", "sortOrder");
    private final String ATTRIBUTE_VALUES_NO_PROGRAM_QUERY = String.format(
            "SELECT %s.*, TrackedEntityAttribute.valueType, TrackedEntityAttribute.optionSet FROM %s " +
                    "JOIN %s ON %s.%s = %s.%s " +
                    "JOIN %s ON %s.%s = %s.%s " +
                    "WHERE %s.%s = ? GROUP BY %s.%s",
            "TrackedEntityAttributeValue", "TrackedEntityAttributeValue",
            "ProgramTrackedEntityAttribute", "ProgramTrackedEntityAttribute", "trackedEntityAttribute", "TrackedEntityAttributeValue", "trackedEntityAttribute",
            "TrackedEntityAttribute", "TrackedEntityAttribute", "uid", "TrackedEntityAttributeValue", "trackedEntityAttribute",
            "TrackedEntityAttributeValue", "trackedEntityInstance", "TrackedEntityAttributeValue", "trackedEntityAttribute");
    private static final Set<String> ATTRIBUTE_VALUES_TABLE = new HashSet<>(Arrays.asList("TrackedEntityAttributeValue", "ProgramTrackedEntityAttribute"));

    private final BriteDatabase briteDatabase;
    private final CodeGenerator codeGenerator;
    private final D2 d2;

    private String teiUid;
    private String programUid;


    private static final String SELECT_ENROLLMENT = "SELECT " +
            "Enrollment.uid FROM Enrollment JOIN Program ON Program.uid = Enrollment.program\n" +
            "WHERE Program.uid = ? AND Enrollment.status = ? AND Enrollment.trackedEntityInstance = ?";


    public DashboardRepositoryImpl(CodeGenerator codeGenerator, BriteDatabase briteDatabase, D2 d2) {
        this.briteDatabase = briteDatabase;
        this.codeGenerator = codeGenerator;
        this.d2 = d2;
    }


    @Override
    public void setDashboardDetails(String teiUid, String programUid) {
        this.teiUid = teiUid;
        this.programUid = programUid;
    }

    @Override
    public Observable<List<TrackedEntityAttributeValue>> mainTrackedEntityAttributes(String teiUid) {
        return d2.trackedEntityModule().trackedEntityAttributeValues
                .byTrackedEntityInstance().eq(teiUid)
                .get().toObservable();
    }

    @Override
    public Event updateState(Event eventModel, EventStatus newStatus) {

        try {
            d2.eventModule().events.uid(eventModel.uid()).setStatus(newStatus);
        } catch (D2Error d2Error) {
            Timber.e(d2Error);
        }

        return d2.eventModule().events.uid(eventModel.uid()).blockingGet();
    }

    @Override
    public Observable<List<ProgramStage>> getProgramStages(String programUid) {
        return d2.programModule().programStages.byProgramUid().eq(programUid).get().toObservable();
    }

    @Override
    public Observable<Enrollment> getEnrollment(String programUid, String teiUid) {
        String progId = programUid == null ? "" : programUid;
        String teiId = teiUid == null ? "" : teiUid;
        return Observable.fromCallable(() -> d2.enrollmentModule().enrollments.byTrackedEntityInstance()
                .eq(teiId).byProgram().eq(progId).one().blockingGet());
    }

    @Override
    public Observable<List<Event>> getTEIEnrollmentEvents(String programUid, String teiUid) {
        String progId = programUid == null ? "" : programUid;
        String teiId = teiUid == null ? "" : teiUid;
        return briteDatabase.createQuery(EVENTS_TABLE, EVENTS_QUERY, progId, teiId, progId)
                .mapToList(cursor -> {
                    Event eventModel = Event.create(cursor);
                    if (Boolean.FALSE.equals(d2.programModule().programs.uid(programUid).blockingGet().ignoreOverdueEvents()))
                        if (eventModel.status() == EventStatus.SCHEDULE && eventModel.dueDate().before(DateUtils.getInstance().getToday()))
                            eventModel = updateState(eventModel, EventStatus.OVERDUE);

                    return eventModel;
                });
    }

    @Override
    public Observable<List<Event>> getEnrollmentEventsWithDisplay(String programUid, String teiUid) {
        String progId = programUid == null ? "" : programUid;
        String teiId = teiUid == null ? "" : teiUid;
        return briteDatabase.createQuery(EVENTS_PROGRAM_STAGE_TABLE, EVENTS_DISPLAY_BOX, progId, teiId, "1")
                .mapToList(Event::create);
    }


    @Override
    public Observable<ProgramStage> displayGenerateEvent(String eventUid) {
        String id = eventUid == null ? "" : eventUid;
        return d2.eventModule().events.uid(id).get()
                .flatMap(event -> d2.programModule().programStages.uid(event.programStage()).get())
                .toObservable();
    }


    // TODO @var value must be double
    @Override
    public Observable<Trio<ProgramIndicator, String, String>> getLegendColorForIndicator(ProgramIndicator indicator, String value) {
        String piId = indicator != null && indicator.uid() != null ? indicator.uid() : "";
        return d2.programModule().programIndicators.withLegendSets().uid(piId).get().toObservable()
                .map(programIndicator -> {
                    String color = "";
                    if (programIndicator != null && programIndicator.legendSets() != null)
                    for(LegendSet legendSet: programIndicator.legendSets()){
                        if (legendSet != null && legendSet.legends() != null)
                        for(Legend legend :legendSet.legends()){
                            Double valueDouble = 0.0;
                            try {
                                valueDouble = Double.parseDouble(value);
                            } catch (Exception e) { }
                            if(legend.startValue() > valueDouble && legend.endValue() < valueDouble)
                            color = legend.color();
                        }
                    }
                    return Trio.create(indicator, value, color);
                });
    }

    @Override
    public Integer getObjectStyle(Context context, String uid) {
        String GET_OBJECT_STYLE = "SELECT * FROM ObjectStyle WHERE uid = ?";
        try (Cursor objectStyleCurosr = briteDatabase.query(GET_OBJECT_STYLE, uid)) {
            if (objectStyleCurosr != null && objectStyleCurosr.moveToNext()) {
                String iconName = objectStyleCurosr.getString(objectStyleCurosr.getColumnIndex("icon"));
                Resources resources = context.getResources();
                iconName = iconName.startsWith("ic_") ? iconName : "ic_" + iconName;
                objectStyleCurosr.close();
                return resources.getIdentifier(iconName, "drawable", context.getPackageName());
            } else
                return R.drawable.ic_person;
        }
    }

    @Override
    public Observable<List<Pair<RelationshipType, String>>> relationshipsForTeiType(String teType) {
        String RELATIONSHIP_QUERY =
                "SELECT FROMTABLE.*, TOTABLE.trackedEntityType AS toTeiType FROM " +
                        "(SELECT RelationshipType.*,RelationshipConstraint.* FROM RelationshipType " +
                        "JOIN RelationshipConstraint ON RelationshipConstraint.relationshipType = RelationshipType.uid WHERE constraintType = 'FROM') " +
                        "AS FROMTABLE " +
                        "JOIN " +
                        "(SELECT RelationshipType.*,RelationshipConstraint.* FROM RelationshipType " +
                        "JOIN RelationshipConstraint ON RelationshipConstraint.relationshipType = RelationshipType.uid WHERE constraintType = 'TO') " +
                        "AS TOTABLE " +
                        "ON TOTABLE.relationshipType = FROMTABLE.relationshipType " +
                        "WHERE FROMTABLE.trackedEntityType = ?";
        return briteDatabase.createQuery("SystemInfo", "SELECT version FROM SystemInfo")
                .mapToOne(cursor -> cursor.getString(0))
                .flatMap(version -> {
                    if (version.equals("2.29"))
                        return d2.relationshipModule().relationshipTypes.get().toObservable()
                                .flatMapIterable(list -> list)
                                .map(relationshipType -> Pair.create(relationshipType, teType))
                                .toList().toObservable();
                    else
                        return briteDatabase.createQuery("RelationshipType", RELATIONSHIP_QUERY, teType)
                                .mapToList(cursor -> Pair.create(RelationshipType.create(cursor), cursor.getString(cursor.getColumnIndex("toTeiType"))));
                });

    }

    @Override
    public Observable<CategoryCombo> catComboForProgram(String programUid) {
        return Observable.defer(() -> Observable.just(d2.categoryModule().categoryCombos.uid(d2.programModule().programs.uid(programUid).blockingGet().categoryCombo().uid()).withAllChildren().blockingGet()))
                .map(categoryCombo -> {
                    List<Category> fullCategories = new ArrayList<>();
                    List<CategoryOptionCombo> fullOptionCombos = new ArrayList<>();
                    for (Category category : categoryCombo.categories()) {
                        fullCategories.add(d2.categoryModule().categories.uid(category.uid()).withAllChildren().blockingGet());
                    }
                    for (CategoryOptionCombo categoryOptionCombo : categoryCombo.categoryOptionCombos())
                        fullOptionCombos.add(d2.categoryModule().categoryOptionCombos.uid(categoryOptionCombo.uid()).withAllChildren().blockingGet());
                    return categoryCombo.toBuilder().categories(fullCategories).categoryOptionCombos(fullOptionCombos).build();
                });
    }

    @Override
    public void setDefaultCatOptCombToEvent(String eventUid) {
        List<CategoryCombo> categoryCombos = d2.categoryModule().categoryCombos.byIsDefault().isTrue().withAllChildren().blockingGet();
        try {
            d2.eventModule().events.uid(eventUid).setAttributeOptionComboUid(categoryCombos.get(0).categoryOptionCombos().get(0).uid());
        } catch (D2Error d2Error) {
            Timber.e(d2Error);
        }
    }

    @Override
    public Observable<String> getAttributeImage(String teiUid) {
        return Observable.fromCallable(() -> {
            Iterator<TrackedEntityAttribute> iterator = d2.trackedEntityModule().trackedEntityAttributes
                    .byValueType().eq(ValueType.IMAGE)
                    .blockingGet().iterator();
            List<String> attrUids = new ArrayList<>();
            while (iterator.hasNext())
                attrUids.add(iterator.next().uid());

            String attrUid = d2.trackedEntityModule().trackedEntityAttributeValues
                    .byTrackedEntityInstance().eq(teiUid)
                    .byTrackedEntityAttribute().in(attrUids)
                    .one().blockingGet().trackedEntityAttribute();

            return FileResourcesUtil.generateFileName(teiUid, attrUid);
        });
    }

    @Override
    public Observable<List<TrackedEntityAttributeValue>> getTEIAttributeValues(String programUid, String teiUid) {
        if (programUid != null)
            return briteDatabase.createQuery(ATTRIBUTE_VALUES_TABLE, ATTRIBUTE_VALUES_QUERY, programUid, teiUid == null ? "" : teiUid)
                    .mapToList(cursor -> ValueUtils.transform(briteDatabase, cursor));
        else
            return briteDatabase.createQuery(ATTRIBUTE_VALUES_TABLE, ATTRIBUTE_VALUES_NO_PROGRAM_QUERY, teiUid == null ? "" : teiUid)
                    .mapToList(cursor -> ValueUtils.transform(briteDatabase, cursor));
    }

    @Override
    public Flowable<List<ProgramIndicator>> getIndicators(String programUid) {
        return d2.programModule().programIndicators.byProgramUid().eq(programUid).withLegendSets().get().toFlowable();
    }

    @Override
    public boolean setFollowUp(String enrollmentUid) {

        boolean followUp = Boolean.TRUE.equals(d2.enrollmentModule().enrollments.uid(enrollmentUid).blockingGet().followUp());
        try {
            d2.enrollmentModule().enrollments.uid(enrollmentUid).setFollowUp(!followUp);
            return !followUp;
        } catch (D2Error d2Error) {
            Timber.e(d2Error);
            return followUp;
        }
    }

    @Override
    public Flowable<List<Note>> getNotes(String programUid, String teUid) {
        //TODO set order by note.storeDate DESC. Does not have the order into the module
        return d2.enrollmentModule().enrollments.withNotes()
                .byProgram().eq(programUid)
                .byTrackedEntityInstance().eq(teUid)
                .get()
                .map(programs -> {
                    List<Note> notes = new ArrayList<>();
                    for (Enrollment enrollment: programs) {
                        if (enrollment.notes() != null)
                            notes.addAll(enrollment.notes());
                    }
                    return notes;
                }).toFlowable();
    }

    private static final String SELECT_USERNAME = "SELECT " +
            "UserCredentials.displayName FROM UserCredentials";

    @Override
    public Consumer<Pair<String, Boolean>> handleNote() {
        return stringBooleanPair -> {
            if (stringBooleanPair.val1()) {

                try (Cursor cursor1 = briteDatabase.query(SELECT_ENROLLMENT, programUid == null ? "" : programUid, EnrollmentStatus.ACTIVE.name(), teiUid == null ? "" : teiUid);
                     Cursor cursor = briteDatabase.query(SELECT_USERNAME)) {
                    cursor.moveToFirst();
                    String userName = cursor.getString(0);

                    cursor1.moveToFirst();
                    String enrollmentUid = cursor1.getString(0);

                    SQLiteStatement insetNoteStatement = briteDatabase.getWritableDatabase()
                            .compileStatement(INSERT_NOTE);

                    sqLiteBind(insetNoteStatement, 1, codeGenerator.generate()); //enrollment
                    sqLiteBind(insetNoteStatement, 2, enrollmentUid == null ? "" : enrollmentUid); //enrollment
                    sqLiteBind(insetNoteStatement, 3, stringBooleanPair.val0() == null ? "" : stringBooleanPair.val0()); //value
                    sqLiteBind(insetNoteStatement, 4, userName == null ? "" : userName); //storeBy
                    sqLiteBind(insetNoteStatement, 5, DateUtils.databaseDateFormat().format(Calendar.getInstance().getTime())); //storeDate
                    sqLiteBind(insetNoteStatement, 6, State.TO_POST.name()); //state

                    long inserted = briteDatabase.executeInsert("Note", insetNoteStatement);

                    if (inserted != -1) {
                        updateEnrollmentState(enrollmentUid);
                        updateTeiState();
                    }

                    insetNoteStatement.clearBindings();
                }
            }
        };
    }

    @Override
    public Flowable<Long> updateEnrollmentStatus(@NonNull String uid, @NonNull EnrollmentStatus value) {
        return Flowable
                .defer(() -> {
                    //UPDATE ENROLLMENT
                    Enrollment enrollment = d2.enrollmentModule().enrollments.uid(uid).blockingGet();
                    ContentValues cv = enrollment.toContentValues();
                    cv.put("lastUpdated", DateUtils.databaseDateFormat().format(Calendar.getInstance().getTime()));
                    cv.put("status", value.name());
                    cv.put("state", enrollment.state() == State.TO_POST ? State.TO_POST.name() : State.TO_UPDATE.name());
                    long updated = briteDatabase.update("Enrollment", cv, "uid = ?", enrollment.uid());

                    updateTeiState();
                    return Flowable.just(updated);
                });
    }


    @Override
    public Observable<TrackedEntityInstance> getTrackedEntityInstance(String teiUid) {
        return Observable.fromCallable(() -> d2.trackedEntityModule().trackedEntityInstances.byUid().eq(teiUid).one().blockingGet());
    }

    @Override
    public Observable<List<ProgramTrackedEntityAttribute>> getProgramTrackedEntityAttributes(String programUid) {
        if (programUid != null)
            return Observable.fromCallable(() -> d2.programModule().programs.withProgramTrackedEntityAttributes().byUid().eq(programUid).one().blockingGet().programTrackedEntityAttributes());
        else
            return Observable.fromCallable(() -> d2.trackedEntityModule().trackedEntityAttributes.byDisplayInListNoProgram().eq(true).blockingGet())
                    .map(trackedEntityAttributes -> {
                        List<Program> programs =
                                d2.programModule().programs.withProgramTrackedEntityAttributes().blockingGet();
                        List<String> teaUids = UidsHelper.getUidsList(trackedEntityAttributes);
                        List<ProgramTrackedEntityAttribute> programTrackedEntityAttributes = new ArrayList<>();
                        for (Program program : programs) {
                            for (ProgramTrackedEntityAttribute pteattr : program.programTrackedEntityAttributes()) {
                                if (teaUids.contains(pteattr.uid()))
                                    programTrackedEntityAttributes.add(pteattr);
                            }
                        }
                        return programTrackedEntityAttributes;
                    });
    }


    @Override
    public Observable<List<OrganisationUnit>> getTeiOrgUnits(@NonNull String teiUid, @Nullable String programUid) {
        EnrollmentCollectionRepository enrollmentRepo = d2.enrollmentModule().enrollments.withAllChildren().byTrackedEntityInstance().eq(teiUid);
        if (programUid != null) {
            enrollmentRepo = enrollmentRepo.byProgram().eq(programUid);
        }

        return enrollmentRepo.get().toObservable()
                .map(enrollments -> {
                    List<String> orgUnitIds = new ArrayList<>();
                    for (Enrollment enrollment : enrollments) {
                        orgUnitIds.add(enrollment.organisationUnit());
                    }
                    return d2.organisationUnitModule().organisationUnits.byUid().in(orgUnitIds).blockingGet();
                });
    }

    @Override
    public Observable<List<Program>> getTeiActivePrograms(String teiUid, boolean showOnlyActive) {
        EnrollmentCollectionRepository enrollmentRepo = d2.enrollmentModule().enrollments.byTrackedEntityInstance().eq(teiUid);
        if (showOnlyActive)
            enrollmentRepo.byStatus().eq(EnrollmentStatus.ACTIVE);
        return enrollmentRepo.get().toObservable().flatMapIterable(enrollments -> enrollments)
                .map(Enrollment::program)
                .toList().toObservable()
                .map(programUids -> d2.programModule().programs.byUid().in(programUids).withStyle().blockingGet());
    }

    @Override
    public Observable<List<Enrollment>> getTEIEnrollments(String teiUid) {
        return d2.enrollmentModule().enrollments.byTrackedEntityInstance().eq(teiUid).get().toObservable();
    }

    @Override
    public void saveCatOption(String eventUid, String catOptionComboUid) {
        // TODO: we need to use the sdk, when the setAttributeOptionCombo() method on the EventObjectRepository is available
        ContentValues event = new ContentValues();
        event.put(EventFields.ATTRIBUTE_OPTION_COMBO, catOptionComboUid);
        briteDatabase.update(EventTableInfo.TABLE_INFO.name(), event, EventFields.UID + " = ?", eventUid == null ? "" : eventUid);
    }

    private void updateEnrollmentState(String enrollmentUid) {
        Enrollment enrollment = d2.enrollmentModule().enrollments.uid(enrollmentUid).blockingGet();
        ContentValues cv = enrollment.toContentValues();
        cv.put("lastUpdated", DateUtils.databaseDateFormat().format(Calendar.getInstance().getTime()));
        cv.put("state", enrollment.state() == State.TO_POST ? State.TO_POST.name() : State.TO_UPDATE.name());
        long updated = briteDatabase.update("Enrollment", cv, "uid = ?", enrollment.uid());
    }

    @Override
    public void updateTeiState() {
        TrackedEntityInstance tei =
                d2.trackedEntityModule().trackedEntityInstances.uid(teiUid).blockingGet();
        ContentValues cv = tei.toContentValues();
        cv.put(TrackedEntityInstance.Columns.STATE, tei.state() == State.TO_POST ? State.TO_POST.name() : State.TO_UPDATE.name());
        cv.put("lastUpdated", DateUtils.databaseDateFormat().format(Calendar.getInstance().getTime()));
        briteDatabase.update("TrackedEntityInstance", cv, "uid = ?", teiUid);
    }
}