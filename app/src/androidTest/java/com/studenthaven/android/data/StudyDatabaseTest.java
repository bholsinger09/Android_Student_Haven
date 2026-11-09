package com.studenthaven.android.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import android.content.Context;
import android.database.Cursor;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.studenthaven.android.data.StudyDatabase.Assignment;
import com.studenthaven.android.data.StudyDatabase.Course;
import com.studenthaven.android.data.StudyDatabase.Flashcard;
import com.studenthaven.android.data.StudyDatabase.Note;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StudyDatabaseTest {
    private static final String DATABASE = "student-haven-instrumentation.db";
    private Context context;
    private StudyDatabase database;

    @Before public void openDatabase() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DATABASE);
        database = Room.databaseBuilder(context, StudyDatabase.class, DATABASE).build();
    }

    @After public void closeDatabase() {
        database.close();
        context.deleteDatabase(DATABASE);
    }

    @Test public void persistsAfterReopeningAndCascadesOnlyDeletedCourse() {
        Course course = new Course();
        course.name = "Biology";
        course.code = "BIO101";
        database.studyDao().insertCourse(course);
        Course other = new Course();
        other.name = "Chemistry";
        database.studyDao().insertCourse(other);
        Note note = new Note();
        note.courseId = course.id;
        note.title = "Cells";
        database.studyDao().insertNote(note);
        Flashcard card = new Flashcard();
        card.courseId = course.id;
        database.studyDao().insertFlashcards(List.of(card));
        Assignment assignment = new Assignment();
        assignment.courseId = course.id;
        database.studyDao().insertAssignment(assignment);
        database.close();
        database = Room.databaseBuilder(context, StudyDatabase.class, DATABASE).build();
        assertEquals(2, count("courses"));
        assertEquals(1, count("notes"));
        assertEquals(1, count("flashcards"));
        assertEquals(1, count("assignments"));
        database.studyDao().deleteCourse(course);
        assertEquals(1, count("courses"));
        assertEquals(0, count("notes"));
        assertEquals(0, count("flashcards"));
        assertEquals(0, count("assignments"));
    }

    @Test public void missingUpdateIsReportedInsteadOfPretendingSuccess() {
        RoomStudyRepository repository = new RoomStudyRepository(database);
        assertThrows(IllegalStateException.class, () -> repository.saveCourse(new Course(), true));
    }

    @Test public void deletingSourceNotePreservesFlashcardAndClearsItsReference() {
        Course course = new Course();
        database.studyDao().insertCourse(course);
        Note note = new Note();
        note.courseId = course.id;
        database.studyDao().insertNote(note);
        Flashcard card = new Flashcard();
        card.courseId = course.id;
        card.sourceNoteId = note.id;
        database.studyDao().insertFlashcards(List.of(card));
        database.studyDao().deleteNote(note);
        assertEquals(1, count("flashcards"));
        try (Cursor cursor = database.getOpenHelper().getReadableDatabase()
                .query("SELECT COUNT(*) FROM flashcards WHERE sourceNoteId IS NULL")) {
            cursor.moveToFirst();
            assertEquals(1, cursor.getInt(0));
        }

    }

    @Test public void savingNoteAndGeneratingCardsIsAtomic() {
        Course course = new Course();
        database.studyDao().insertCourse(course);
        Note note = new Note();
        note.courseId = course.id;
        note.content = "Original";
        database.studyDao().insertNote(note);
        Flashcard card = new Flashcard();
        card.courseId = course.id;
        card.sourceNoteId = note.id;
        database.studyDao().insertFlashcards(List.of(card));
        note.content = "Edited";
        RoomStudyRepository repository = new RoomStudyRepository(database);
        assertThrows(android.database.sqlite.SQLiteConstraintException.class,
                () -> repository.saveNoteAndCards(note, List.of(card)));
        try (Cursor cursor = database.getOpenHelper().getReadableDatabase()
                .query("SELECT content FROM notes WHERE id = ?", new Object[]{note.id})) {
            cursor.moveToFirst();
            assertEquals("Original", cursor.getString(0));
        }
        Flashcard generated = new Flashcard();
        generated.courseId = course.id;
        generated.sourceNoteId = note.id;
        repository.saveNoteAndCards(note, List.of(generated));
        assertEquals(2, count("flashcards"));
        try (Cursor cursor = database.getOpenHelper().getReadableDatabase()
                .query("SELECT content FROM notes WHERE id = ?", new Object[]{note.id})) {
            cursor.moveToFirst();
            assertEquals("Edited", cursor.getString(0));
        }
    }

    private int count(String table) {
        try (Cursor cursor = database.getOpenHelper().getReadableDatabase()
                .query("SELECT COUNT(*) FROM " + table)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }
}
