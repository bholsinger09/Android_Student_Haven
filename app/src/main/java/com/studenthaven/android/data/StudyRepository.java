package com.studenthaven.android.data;

import androidx.lifecycle.LiveData;
import com.studenthaven.android.data.StudyDatabase.Assignment;
import com.studenthaven.android.data.StudyDatabase.Course;
import com.studenthaven.android.data.StudyDatabase.Flashcard;
import com.studenthaven.android.data.StudyDatabase.Note;
import java.util.List;

public interface StudyRepository {
    LiveData<List<Course>> courses();
    List<Course> localCoursesForImport();
    LiveData<List<Note>> notes();
    LiveData<List<Flashcard>> flashcards();
    LiveData<List<Assignment>> assignments();
    void saveCourse(Course course, boolean existing);
    void saveNote(Note note, boolean existing);
    void saveNoteAndCards(Note note, List<Flashcard> cards);
    void addFlashcards(List<Flashcard> cards);
    void saveFlashcard(Flashcard card);
    void saveAssignment(Assignment assignment, boolean existing);
    void deleteCourse(Course course);
    void deleteNote(Note note);
    void deleteFlashcard(Flashcard card);
    void deleteAssignment(Assignment assignment);
}
