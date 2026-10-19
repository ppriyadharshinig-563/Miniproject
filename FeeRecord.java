public class FeeRecord {

    private int studentId;
    private double feeAmount;
    private boolean feePaid;

    public FeeRecord(int studentId, double feeAmount, boolean feePaid) {

        this.studentId = studentId;
        this.feeAmount = feeAmount;
        this.feePaid = feePaid;
    }

    public int getStudentId() {
        return studentId;
    }

    public double getFeeAmount() {
        return feeAmount;
    }

    public boolean isFeePaid() {
        return feePaid;
    }

    public void setFeePaid(boolean feePaid) {
        this.feePaid = feePaid;
    }

    public void displayFeeRecord() {

        System.out.println("-----------------------------");
        System.out.println("Student ID : " + studentId);
        System.out.println("Fee Amount : ₹" + feeAmount);
        System.out.println("Fee Status : "
                + (feePaid ? "Paid" : "Pending"));
    }
}
