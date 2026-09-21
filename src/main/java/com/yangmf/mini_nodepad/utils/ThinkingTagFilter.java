package com.yangmf.mini_nodepad.utils;

public class ThinkingTagFilter {

    private enum State { NORMAL, TAG_OPEN, INSIDE, TAG_CLOSE }

    private State state = State.NORMAL;
    private final StringBuilder tagBuffer = new StringBuilder();

    private static final String OPEN_TAG = "<thinking>";
    private static final String CLOSE_TAG = "</thinking>";

    public String filter(String token) {
        StringBuilder output = new StringBuilder();

        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);

            switch (state) {
                case NORMAL:
                    if (c == '<') {
                        state = State.TAG_OPEN;
                        tagBuffer.setLength(0);
                        tagBuffer.append(c);
                    } else {
                        output.append(c);
                    }
                    break;

                case TAG_OPEN:
                    tagBuffer.append(c);
                    String buf = tagBuffer.toString();
                    if (buf.length() < OPEN_TAG.length()) {
                        if (!OPEN_TAG.startsWith(buf)) {
                            output.append(tagBuffer);
                            state = State.NORMAL;
                            tagBuffer.setLength(0);
                        }
                    } else if (OPEN_TAG.equals(buf)) {
                        state = State.INSIDE;
                        tagBuffer.setLength(0);
                    } else {
                        output.append(tagBuffer);
                        state = State.NORMAL;
                        tagBuffer.setLength(0);
                    }
                    break;

                case INSIDE:
                    if (c == '<') {
                        state = State.TAG_CLOSE;
                        tagBuffer.setLength(0);
                        tagBuffer.append(c);
                    }
                    break;

                case TAG_CLOSE:
                    tagBuffer.append(c);
                    String closeBuf = tagBuffer.toString();
                    if (closeBuf.length() < CLOSE_TAG.length()) {
                        if (!CLOSE_TAG.startsWith(closeBuf)) {
                            state = State.INSIDE;
                            tagBuffer.setLength(0);
                        }
                    } else if (CLOSE_TAG.equals(closeBuf)) {
                        state = State.NORMAL;
                        tagBuffer.setLength(0);
                    } else {
                        state = State.INSIDE;
                        tagBuffer.setLength(0);
                    }
                    break;
            }
        }

        return output.toString();
    }

    public void reset() {
        state = State.NORMAL;
        tagBuffer.setLength(0);
    }
}