// A follow.unfollow body in the shape of X's documented sample (docs.x.com/x-api/activity/event-payloads),
// trimmed to the fields that matter, with ids chosen so the tracked account is the target.
export const MAIN = "YOUR_MAIN_USER_ID";

export function unfollowEvent({ uuid = "-4233426745281461957", sourceId = "1111111111111111111", username = "ExampleUser", targetId = MAIN, type = "follow.unfollow" } = {}) {
  return {
    data: {
      event_uuid: uuid,
      filter: { user_id: MAIN },
      event_type: type,
      tag: "unfollowers",
      payload: {
        source: {
          data: {
            username,
            id: sourceId,
            name: "Example User",
            created_at: "2023-10-23T13:44:53.000Z",
            public_metrics: { followers_count: 5502, followers_count_str: "5502", following_count: 651, following_count_str: "651" },
            protected: false,
          },
        },
        target: { data: { username: "main_account", id: targetId, name: "Example", public_metrics: { followers_count: 24027, following_count: 12 } } },
      },
    },
  };
}
