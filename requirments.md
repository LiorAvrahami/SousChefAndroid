years ago I half made this web app:
<https://github.com/LiorAvrahami/SousChef>

I want you to turn it into a fully working app. the idea is that it's supposed to be a super comfertable interface for quickly and with low effort looking up recipies and tweaking them. with an in built timer and it goes over the recipies bit by bit.

optimziation - the app should optimzie the recipie by tweaking the time and amounts of ingrediants. each tweak tothe recipie is a version. the user can also eneter versions, for example if they want to try flipping the order of when you put in some ingrediants. the optimizer should preform global & local optimziation

recipie view:
there are two ways to start cooking a recipie.

- optimal version so far.
- exploration - gather information for optimization.

interface should be big. every step is a page, when the step is done you slide to the next page some page might be a timer. if you slide down then it opens the recipie view which a more holistic view of the recipie and also from there you can exit this cooking session. once a cooking session is done you ener how well it is, whether too watery, whether to burnt, how good overall and so on. this will help the optimizer. there will be two options for optimizer, one is classical opzimize the overall score, and the other is based on some very cheap llm service to look at all the previous versions and their scores and comments in order to suggest a tweak to the recipe. for this we will need two agents, one that will suggest tweak one that will check that the tweak is not too much.

in parts in the recipe that are waiting, the page waits for that time and then plays a timer when it is done. it's important that this timer be reliable.
