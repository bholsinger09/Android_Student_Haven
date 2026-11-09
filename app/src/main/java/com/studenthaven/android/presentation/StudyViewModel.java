package com.studenthaven.android.presentation;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.data.StudyDatabase.Assignment;
import com.studenthaven.android.data.StudyDatabase.Course;
import com.studenthaven.android.data.StudyDatabase.Flashcard;
import com.studenthaven.android.data.StudyDatabase.Note;
import com.studenthaven.android.data.StudyRepository;
import com.studenthaven.android.domain.CalculateGradeUseCase;
import com.studenthaven.android.domain.GenerateFlashcardsUseCase;
import com.studenthaven.android.domain.ReviewFlashcardUseCase;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

public final class StudyViewModel extends AndroidViewModel {
    public record Snapshot(List<Course> courses, List<Note> notes,
                           List<Flashcard> cards, List<Assignment> assignments, boolean loaded) {}
    public record Operation(boolean busy, boolean success, String error) {}

    private final StudyRepository repository;
    private final ExecutorService io;
    private final SavedStateHandle savedState;
    private final MediatorLiveData<Snapshot> snapshot = new MediatorLiveData<>();
    private final MutableLiveData<Operation> operation =
            new MutableLiveData<>(new Operation(false, false, null));
    private List<Course> courses;
    private List<Note> notes;
    private List<Flashcard> cards;
    private List<Assignment> assignments;

    public StudyViewModel(@NonNull Application application, SavedStateHandle savedState) {
        super(application);
        this.savedState = savedState;
        HavenApplication app = (HavenApplication) application;
        repository = app.repository();
        io = app.io();
        snapshot.addSource(repository.courses(), values -> { courses = values; publish(); });
        snapshot.addSource(repository.notes(), values -> { notes = values; publish(); });
        snapshot.addSource(repository.flashcards(), values -> { cards = values; publish(); });
        snapshot.addSource(repository.assignments(), values -> { assignments = values; publish(); });
    }

    public LiveData<Snapshot> snapshot() { return snapshot; }
    public LiveData<Operation> operation() { return operation; }
    public LiveData<Integer> page() { return savedState.getLiveData("page", 0); }
    public void selectPage(int page) { savedState.set("page", page); }
    public String selectedCourse() { return savedState.get("course"); }
    public void selectCourse(String id) { savedState.set("course", id); }
    public String search() { return savedState.getLiveData("search", "").getValue(); }
    public void search(String query) { savedState.set("search", query); }

    private void publish() {
        snapshot.setValue(new Snapshot(
                courses == null ? List.of() : courses,
                notes == null ? List.of() : notes,
                cards == null ? List.of() : cards,
                assignments == null ? List.of() : assignments,
                courses != null && notes != null && cards != null && assignments != null));
    }

    public void saveCourse(String id, String name, String code, String professor, String location) {
        write(() -> {
            Course course = new Course();
            if (id != null) course.id = id;
            course.name = required(name, "Class name");
            course.code = required(code, "Course code");
            course.professor = professor.trim();
            course.location = location.trim();
            repository.saveCourse(course, id != null);
        });
    }

    public void saveNote(String id, String courseId, String title, String content, String tags) {
        write(() -> {
            Note note = new Note();
            if (id != null) note.id = id;
            note.courseId = required(courseId, "Class");
            note.title = required(title, "Title");
            note.content = required(content, "Note content");
            note.tags = tags.trim();
            repository.saveNote(note, id != null);
        });
    }

    public void saveCard(Flashcard original, String courseId, String front, String back) {
        write(() -> {
            Flashcard card = original == null ? new Flashcard() : copyCard(original);
            card.courseId = required(courseId, "Class");
            card.front = required(front, "Question");
            card.back = required(back, "Answer");
            if (original == null) repository.addFlashcards(List.of(card));
            else repository.saveFlashcard(card);
        });
    }

    public void saveAssignment(String id, String courseId, String name, String due,
                               String score, String maximum, String weight) {
        write(() -> {
            Assignment assignment = new Assignment();
            if (id != null) assignment.id = id;
            assignment.courseId = required(courseId, "Class");
            assignment.name = required(name, "Assignment name");
            try {
                assignment.dueDate = LocalDate.parse(due.trim()).toString();
            } catch (java.time.format.DateTimeParseException error) {
                throw new IllegalArgumentException("Enter a valid due date as YYYY-MM-DD.");
            }
            assignment.score = score.trim().isEmpty() ? null : number(score);
            assignment.maxScore = number(maximum);
            assignment.weight = number(weight);
            new CalculateGradeUseCase().validate(new CalculateGradeUseCase.Mark(
                    assignment.score, assignment.maxScore, assignment.weight));
            repository.saveAssignment(assignment, id != null);
        });
    }

    public void generate(Note note) {
        write(() -> {
            repository.addFlashcards(generatedCards(note));
        });
    }

    public void saveNoteAndGenerate(String id, String courseId, String title, String content, String tags) {
        write(() -> {
            Note note = new Note();
            note.id = required(id, "Note");
            note.courseId = required(courseId, "Class");
            note.title = required(title, "Title");
            note.content = required(content, "Note content");
            note.tags = tags.trim();
            List<Flashcard> generated = generatedCards(note);
            repository.saveNoteAndCards(note, generated);
        });
    }

    private List<Flashcard> generatedCards(Note note) {
        List<Flashcard> generated = new ArrayList<>();
        for (GenerateFlashcardsUseCase.Definition definition :
                new GenerateFlashcardsUseCase().execute(note.content)) {
            Flashcard card = new Flashcard();
            card.courseId = note.courseId;
            card.sourceNoteId = note.id;
            card.front = definition.front();
            card.back = definition.back();
            generated.add(card);
        }
        return generated;
    }

    public void review(Flashcard original, int quality) {
        write(() -> {
            Flashcard card = copyCard(original);
            ReviewFlashcardUseCase.Review review = new ReviewFlashcardUseCase().execute(
                    card.repetitions, card.easeFactor, card.intervalDays, quality, ZonedDateTime.now());
            card.repetitions = review.repetitions();
            card.easeFactor = review.easeFactor();
            card.intervalDays = review.intervalDays();
            card.nextReviewAt = review.nextReviewAt();
            card.lastReviewedAt = review.lastReviewedAt();
            repository.saveFlashcard(card);
        });
    }

    public void deleteCourse(Course course) { write(() -> repository.deleteCourse(course)); }
    public void deleteNote(Note note) { write(() -> repository.deleteNote(note)); }
    public void deleteCard(Flashcard card) { write(() -> repository.deleteFlashcard(card)); }
    public void deleteAssignment(Assignment value) { write(() -> repository.deleteAssignment(value)); }

    public void saveProfile(String name, String college) {
        write(() -> {
            String validName = required(name, "Name");
            String validCollege = required(college, "College");
            boolean saved = getApplication().getSharedPreferences("profile", 0).edit()
                    .putString("name", validName).putString("college", validCollege).commit();
            if (!saved) throw new IllegalStateException("Profile could not be saved.");
        });
    }

    public void saveCollege(String college) {
        write(() -> {
            String validCollege = required(college, "College");
            boolean saved = getApplication().getSharedPreferences("profile", 0).edit()
                    .putString("college", validCollege).commit();
            if (!saved) throw new IllegalStateException("College could not be saved.");
        });
    }

    private void write(Runnable action) {
        if (operation.getValue().busy()) return;
        operation.setValue(new Operation(true, false, null));
        io.execute(() -> {
            try {
                action.run();
                operation.postValue(new Operation(false, true, null));
            } catch (IllegalArgumentException error) {
                Log.w("StudentHaven", "Validation failed", error);
                operation.postValue(new Operation(false, false, error.getMessage()));
            } catch (RuntimeException error) {
                Log.e("StudentHaven", "Persistence operation failed", error);
                operation.postValue(new Operation(false, false,
                        getApplication().getString(com.studenthaven.android.R.string.operation_failed)));
            }
        });
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be empty.");
        }
        return value.trim();
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter valid numeric scores and weights.");
        }
    }

    private static Flashcard copyCard(Flashcard source) {
        Flashcard card = new Flashcard();
        card.id = source.id;
        card.courseId = source.courseId;
        card.front = source.front;
        card.back = source.back;
        card.sourceNoteId = source.sourceNoteId;
        card.repetitions = source.repetitions;
        card.easeFactor = source.easeFactor;
        card.intervalDays = source.intervalDays;
        card.nextReviewAt = source.nextReviewAt;
        card.lastReviewedAt = source.lastReviewedAt;
        return card;
    }
}
