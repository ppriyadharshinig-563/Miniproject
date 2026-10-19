import java.util.ArrayList;
import java.util.Scanner;

public class Main {

    static Scanner sc = new Scanner(System.in);

    static ArrayList<Student> students = new ArrayList<>();
    static ArrayList<CollegeBus> buses = new ArrayList<>();

    public static void main(String[] args) {

        while (true) {

            System.out.println("1. Student Management");
            System.out.println("2. Bus Management");
            System.out.println("3. Attendance");
            System.out.println("4. Bus Requirement");
            System.out.println("5. Check Eligibility");
            System.out.println("6. Exit");

            System.out.print("Enter your choice: ");
            int choice = sc.nextInt();

            switch (choice) {

                case 1:
                    studentManagement();
                    break;

                case 2:
                    busManagement();
                    break;

                case 3:
                    attendance();
                    break;

                case 4:
                    busRequirement();
                    break;

                case 5:
                    checkEligibility();
                    break;

                case 6:
                System.out.println("Thank you!");
                return;
            }
        }
    }

    // STUDENT MANAGEMENT

    public static void studentManagement() {

        while (true) {

            System.out.println("\n===== STUDENT MANAGEMENT =====");
            System.out.println("1. Add Student");
            System.out.println("2. View Students");
            System.out.println("3. Back");

            System.out.print("Enter your choice: ");
            int choice = sc.nextInt();

            switch (choice) {

                case 1:
                    addStudent();
                    break;

                case 2:
                    viewStudents();
                    break;

                case 3:
                    return;

                default:
                    System.out.println("Invalid choice!");
            }
        }
    }

    public static void addStudent() {

        System.out.print("Enter Student ID: ");
        int id = sc.nextInt();

        sc.nextLine();

        System.out.print("Enter Student Name: ");
        String name = sc.nextLine();

        System.out.print("Enter Pickup Stop: ");
        String stop = sc.nextLine();

        System.out.print("Transport Authorized (true/false): ");
        boolean authorized = sc.nextBoolean();

        Student student =
            new Student(id, name, stop, authorized);

        students.add(student);

        System.out.println("Student added successfully!");
    }

    public static void viewStudents() {

        if (students.isEmpty()) {
            System.out.println("No students available.");
            return;
        }

        System.out.println("\n===== STUDENT LIST =====");

        for (Student student : students) {
            student.displayStudent();
        }
    }

    // BUS MANAGEMENT

    public static void busManagement() {

        while (true) {

            System.out.println("\n===== BUS MANAGEMENT =====");
            System.out.println("1. Add Bus");
            System.out.println("2. View Buses");
            System.out.println("3. Back");

            System.out.print("Enter your choice: ");
            int choice = sc.nextInt();

            switch (choice) {

                case 1:
                    addBus();
                    break;

                case 2:
                    viewBuses();
                    break;

                case 3:
                    return;

                default:
                    System.out.println("Invalid choice!");
            }
        }
    }

    public static void addBus() {

        System.out.print("Enter Bus ID: ");
        int id = sc.nextInt();

        sc.nextLine();

        System.out.print("Enter Bus Number: ");
        String busNumber = sc.nextLine();

        System.out.print("Enter Bus Capacity: ");
        int capacity = sc.nextInt();

        CollegeBus bus =
            new CollegeBus(id, busNumber, capacity);

        buses.add(bus);

        System.out.println("Bus added successfully!");
    }

    public static void viewBuses() {

        if (buses.isEmpty()) {
            System.out.println("No buses available.");
            return;
        }

        System.out.println("\n===== BUS LIST =====");

        for (CollegeBus bus : buses) {
            bus.displayBus();
        }
    }

    // ATTENDANCE

    public static void attendance() {

        if (students.isEmpty()) {
            System.out.println("No students available.");
            return;
        }

        System.out.println("\n===== DAILY ATTENDANCE =====");

        for (Student student : students) {

            System.out.println("\nStudent ID: "
                    + student.getStudentId());

            System.out.println("Name: "
                    + student.getName());

            System.out.print("Present? (true/false): ");

            boolean present = sc.nextBoolean();

            student.setPresent(present);
        }

        System.out.println("\nAttendance updated successfully!");
    }
    public static void busRequirement() {

    if (students.isEmpty()) {
        System.out.println("No students available.");
        return;
    }

    System.out.println("\n===== BUS REQUIREMENT =====");

    for (Student student : students) {

        System.out.println("\nStudent ID: " + student.getStudentId());
        System.out.println("Name: " + student.getName());
        System.out.print("Bus Required Tomorrow? (true/false): ");

        boolean required = sc.nextBoolean();

        student.setBusRequired(required);
    }

    System.out.println("\nBus requirement updated successfully!");
}
public static void checkEligibility() {

    if (students.isEmpty()) {
        System.out.println("No students available.");
        return;
    }

    System.out.println("\n===== ELIGIBLE STUDENTS =====");

    boolean found = false;

    for (Student student : students) {

        if (student.isTransportAuthorized()
                && student.isPresent()
                && student.isBusRequired()) {

            System.out.println(
                student.getStudentId() + " - "
                + student.getName() + " - "
                + student.getPickupStop()
            );

            found = true;
        }
    }

    if (!found) {
        System.out.println("No eligible students.");
    }
}
}
