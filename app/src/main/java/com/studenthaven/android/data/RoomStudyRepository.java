package com.studenthaven.android.data;

import androidx.lifecycle.LiveData;
import com.studenthaven.android.data.StudyDatabase.Assignment;
import com.studenthaven.android.data.StudyDatabase.Course;
import com.studenthaven.android.data.StudyDatabase.Flashcard;
import com.studenthaven.android.data.StudyDatabase.Note;
import java.util.List;

public final class RoomStudyRepository implements StudyRepository {
    private final StudyDatabase.StudyDao dao;
    private final StudyDatabase database;

    public RoomStudyRepository(StudyDatabase database) {
        this.database = database;
        dao = database.studyDao();
    }

    @Override public LiveData<List<Course>> courses() { return dao.observeCourses(); }
    @Override public List<Course> localCoursesForImport() { return dao.listCourses(); }
    @Override public LiveData<List<Note>> notes() { return dao.observeNotes(); }
    @Override public LiveData<List<Flashcard>> flashcards() { return dao.observeFlashcards(); }
    @Override public LiveData<List<Assignment>> assignments() { return dao.observeAssignments(); }
    @Override public void saveCourse(Course course, boolean existing) {
        if (existing) requireUpdated(dao.updateCourse(course));
        else dao.insertCourse(course);
    }
    @Override public void saveNote(Note note, boolean existing) {
        if (existing) requireUpdated(dao.updateNote(note));
        else dao.insertNote(note);
    }
    @Override public void addFlashcards(List<Flashcard> cards) { dao.insertFlashcards(cards); }
    @Override public void saveNoteAndCards(Note note, List<Flashcard> cards) {
        database.runInTransaction(() -> {
            requireUpdated(dao.updateNote(note));
            dao.insertFlashcards(cards);
        });
    }
    @Override public void saveFlashcard(Flashcard card) { requireUpdated(dao.updateFlashcard(card)); }
    @Override public void saveAssignment(Assignment assignment, boolean existing) {
        if (existing) requireUpdated(dao.updateAssignment(assignment));
        else dao.insertAssignment(assignment);
    }
    @Override public void deleteCourse(Course course) { requireUpdated(dao.deleteCourse(course)); }
    @Override public void deleteNote(Note note) { requireUpdated(dao.deleteNote(note)); }
    @Override public void deleteFlashcard(Flashcard card) { requireUpdated(dao.deleteFlashcard(card)); }
    @Override public void deleteAssignment(Assignment assignment) {
        requireUpdated(dao.deleteAssignment(assignment));
    }

    private static void requireUpdated(int count) {
        if (count != 1) throw new IllegalStateException("The item no longer exists. Reload and retry.");
    }
}
