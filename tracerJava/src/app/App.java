package app;

import core.Tracer;

public class App {

    public static void main(String[] args) throws Exception {
        new Tracer(
                "Hello.java",
                "import java.util.*;\n" +
                        "" +
                        "public class Hello {\n" +
                        "    public static void main(String[] args) {\n" +
                        "        System.out.println(\"from hello\");\n" +
                        "        var x = 0;\n" +
                        "        var y = -123l;\n" +
                        "        var a = new int[]{1,2,3,4};\n" +
                        "        var b = new String[]{\"a\",\"b\", \"c\", \"d\"};\n" +
                        "        var c = new HashSet<String>();\n" +
                        "        var d = new HashMap<Integer, String>();\n" +
                        "        //var scan = new Scanner(System.in);\n" +
                        "        //var input = scan.nextLine();\n" +
                        "        //System.out.println(input);\n" +
                        "    }\n" +
                        "}\n"
        ).run();
    }
}