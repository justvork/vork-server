package sh.vork.github.contribution;

/**
 * Canonical contribution file payload written into a repository branch.
 */
public record ContributionFile(
        String path,
                String content,
                String encoding
) {

        public static final String ENCODING_TEXT = "text";
        public static final String ENCODING_BASE64 = "base64";

        public ContributionFile(String path, String content) {
                this(path, content, ENCODING_TEXT);
        }

        public ContributionFile {
                if (encoding == null || encoding.isBlank()) {
                        encoding = ENCODING_TEXT;
                } else {
                        encoding = encoding.trim().toLowerCase();
                }
        }

        public static ContributionFile text(String path, String content) {
                return new ContributionFile(path, content, ENCODING_TEXT);
        }

        public static ContributionFile base64(String path, String base64Content) {
                return new ContributionFile(path, base64Content, ENCODING_BASE64);
        }
}
